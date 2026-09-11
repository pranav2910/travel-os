package io.travelos.supplier.sandbox;

import io.travelos.common.ids.IdPrefix;
import io.travelos.common.ids.Ids;
import io.travelos.contracts.common.v1.Cabin;
import io.travelos.contracts.common.v1.TimeWindow;
import io.travelos.contracts.offer.v1.Offer;
import io.travelos.contracts.supplier.v1.CancelOrderRequest;
import io.travelos.contracts.supplier.v1.CancelOrderResponse;
import io.travelos.contracts.supplier.v1.ChangeOrderRequest;
import io.travelos.contracts.supplier.v1.ChangeOrderResponse;
import io.travelos.contracts.supplier.v1.CreateOrderRequest;
import io.travelos.contracts.supplier.v1.CreateOrderResponse;
import io.travelos.contracts.supplier.v1.Passenger;
import io.travelos.contracts.supplier.v1.PriceOfferRequest;
import io.travelos.contracts.supplier.v1.PriceOfferResponse;
import io.travelos.contracts.supplier.v1.SearchAirRequest;
import io.travelos.contracts.supplier.v1.SearchAirResponse;
import io.travelos.contracts.supplier.v1.SupplierOrderStatus;
import io.travelos.supplier.AirSupplier;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * A supplier that behaves like a well-mannered airline API: deterministic inventory, offers that
 * expire, prices that must be confirmed before booking, orders that are idempotent by the caller's
 * key, and documented ways to make it fail.
 */
@Component
public class SandboxAirSupplier implements AirSupplier {

  private static final String LOCATOR_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

  private final SandboxOrderRepository orders;
  private final Clock clock;

  public SandboxAirSupplier(SandboxOrderRepository orders, Clock clock) {
    this.orders = orders;
    this.clock = clock;
  }

  @Override
  public String provider() {
    return SandboxInventory.PROVIDER;
  }

  @Override
  public SearchAirResponse search(SearchAirRequest request) {
    if ("ZZZ".equals(request.getDestination())) {
      throw new SupplierException(
          "UNAVAILABLE", "sandbox-air is simulating an outage for ZZZ", true);
    }
    if (request.getOrigin().isBlank() || request.getDestination().isBlank()) {
      throw new SupplierException("INVALID_ROUTE", "origin and destination are required", false);
    }
    if (!request.hasOutboundDeparture() || !request.getOutboundDeparture().hasNotBefore()) {
      throw new SupplierException(
          "INVALID_WINDOW", "outbound_departure.not_before is required", false);
    }
    Instant now = clock.instant();
    LocalDate outboundDate = date(request.getOutboundDeparture().getNotBefore().getSeconds());
    LocalDate inboundDate =
        request.hasReturnDeparture() && request.getReturnDeparture().hasNotBefore()
            ? date(request.getReturnDeparture().getNotBefore().getSeconds())
            : null;
    Set<Cabin> cabins =
        request.getCabinsList().isEmpty()
            ? EnumSet.of(Cabin.ECONOMY)
            : EnumSet.copyOf(request.getCabinsList());
    String session = Ids.newId(IdPrefix.SEARCH_SESSION);
    SearchAirResponse.Builder response = SearchAirResponse.newBuilder().setSearchSessionId(session);
    for (SandboxInventory.Schedule s :
        SandboxInventory.schedules(
            request.getOrigin(), request.getDestination(), outboundDate, inboundDate, cabins)) {
      if (!within(s.outbound().getFirst().departure(), request.getOutboundDeparture())) {
        continue;
      }
      if (inboundDate != null
          && (s.inbound().isEmpty()
              || !within(s.inbound().getFirst().departure(), request.getReturnDeparture()))) {
        continue;
      }
      response.addOffers(
          SandboxInventory.toOffer(
              s,
              request.getOrigin(),
              request.getDestination(),
              outboundDate,
              inboundDate,
              now,
              session,
              Ids.newId(IdPrefix.OFFER)));
    }
    return response.build();
  }

  @Override
  public PriceOfferResponse price(PriceOfferRequest request) {
    SandboxOfferId id = SandboxOfferId.decode(request.getProviderOfferId());
    Instant now = clock.instant();
    if (Instant.ofEpochSecond(id.issuedEpochSeconds())
        .plus(SandboxInventory.OFFER_TTL)
        .isBefore(now)) {
      throw new SupplierException("OFFER_EXPIRED", "offer expired; search again", false);
    }
    SandboxInventory.Schedule schedule = schedule(id);
    Offer offer =
        SandboxInventory.toOffer(
            schedule,
            id.origin(),
            id.destination(),
            id.outboundDate(),
            id.inboundDate(),
            now,
            "",
            Ids.newId(IdPrefix.OFFER));
    // Deterministic inventory never moves; a real adapter would compare with the supplier's
    // reprice.
    return PriceOfferResponse.newBuilder().setOffer(offer).setPriceChanged(false).build();
  }

  @Override
  @Transactional
  public CreateOrderResponse createOrder(CreateOrderRequest request) {
    String key = request.getCtx().getIdempotencyKey();
    if (key.isBlank()) {
      throw new SupplierException(
          "IDEMPOTENCY_KEY_REQUIRED", "CreateOrder requires ctx.idempotency_key", false);
    }
    String tenant = request.getCtx().getTenantId();
    Optional<SandboxOrderRepository.SandboxOrder> existing = orders.byIdempotencyKey(tenant, key);
    if (existing.isPresent()) {
      return response(existing.get());
    }
    SandboxOfferId id = SandboxOfferId.decode(request.getProviderOfferId());
    if (id.slot() == SandboxInventory.FAILING_SLOT) {
      throw new SupplierException(
          "SEAT_NO_LONGER_AVAILABLE", "the last seat on this itinerary was just sold", false);
    }
    if ("decline".equals(request.getPaymentToken())) {
      throw new SupplierException("PAYMENT_DECLINED", "payment was declined by the issuer", false);
    }
    if (request.getPassengersCount() == 0) {
      throw new SupplierException(
          "PASSENGER_REQUIRED", "at least one passenger is required", false);
    }
    SandboxInventory.Schedule schedule = schedule(id);
    Instant now = clock.instant();
    String externalOrderId = "SBX-" + Ids.newId(IdPrefix.ORDER).substring(4);
    List<String> passengers =
        request.getPassengersList().stream()
            .map(p -> p.getGivenName() + " " + p.getFamilyName())
            .toList();
    List<String> tickets =
        request.getPassengersList().stream().map(p -> ticket(externalOrderId, p)).toList();
    SandboxOrderRepository.SandboxOrder order =
        new SandboxOrderRepository.SandboxOrder(
            externalOrderId,
            tenant,
            key,
            request.getProviderOfferId(),
            locator(externalOrderId),
            "CONFIRMED",
            "USD",
            schedule.fareMinor() * request.getPassengersCount(),
            passengers,
            tickets,
            now,
            now);
    orders.insert(order);
    return response(order);
  }

  @Override
  public ChangeOrderResponse changeOrder(ChangeOrderRequest request) {
    throw new SupplierException(
        "NOT_IMPLEMENTED", "changes arrive with Slice 2 (disruption recovery)", false);
  }

  @Override
  @Transactional
  public CancelOrderResponse cancelOrder(CancelOrderRequest request) {
    SandboxOrderRepository.SandboxOrder order =
        orders
            .byId(request.getExternalOrderId())
            .orElseThrow(
                () ->
                    new SupplierException(
                        "ORDER_UNKNOWN", "no such order: " + request.getExternalOrderId(), false));
    if (!order.tenantId().equals(request.getCtx().getTenantId())) {
      throw new SupplierException(
          "ORDER_UNKNOWN", "no such order: " + request.getExternalOrderId(), false);
    }
    long refund = order.chargedMinor();
    if (!"CANCELLED".equals(order.status())) {
      SandboxOfferId id = SandboxOfferId.decode(order.providerOfferId());
      refund =
          schedule(id).refundable()
              ? order.chargedMinor()
              : Math.max(0, order.chargedMinor() - 7500);
      orders.updateStatus(
          order.externalOrderId(), "CANCELLED", order.chargedMinor() - refund, clock.instant());
    }
    return CancelOrderResponse.newBuilder()
        .setExternalOrderId(order.externalOrderId())
        .setStatus(SupplierOrderStatus.CANCELLED)
        .setRefund(SandboxInventory.usd(refund))
        .build();
  }

  private static CreateOrderResponse response(SandboxOrderRepository.SandboxOrder order) {
    return CreateOrderResponse.newBuilder()
        .setExternalOrderId(order.externalOrderId())
        .setRecordLocator(order.recordLocator())
        .setStatus(
            "CANCELLED".equals(order.status())
                ? SupplierOrderStatus.CANCELLED
                : SupplierOrderStatus.CONFIRMED)
        .setCharged(SandboxInventory.usd(order.chargedMinor()))
        .addAllTicketNumbers(order.ticketNumbers())
        .build();
  }

  private static SandboxInventory.Schedule schedule(SandboxOfferId id) {
    Cabin cabin = Cabin.valueOf(id.cabin());
    return SandboxInventory.schedules(
            id.origin(), id.destination(), id.outboundDate(), id.inboundDate(), EnumSet.of(cabin))
        .stream()
        .filter(s -> s.slot() == id.slot())
        .findFirst()
        .orElseThrow(
            () -> new SupplierException("OFFER_UNKNOWN", "offer slot no longer exists", false));
  }

  private static boolean within(Instant departure, TimeWindow window) {
    if (window.hasNotBefore() && departure.getEpochSecond() < window.getNotBefore().getSeconds()) {
      return false;
    }
    return !window.hasNotAfter() || departure.getEpochSecond() <= window.getNotAfter().getSeconds();
  }

  private static LocalDate date(long epochSeconds) {
    return Instant.ofEpochSecond(epochSeconds).atOffset(ZoneOffset.UTC).toLocalDate();
  }

  private static String locator(String externalOrderId) {
    StringBuilder sb = new StringBuilder();
    long h = Math.floorMod((long) externalOrderId.hashCode(), 1L << 30);
    for (int i = 0; i < 6; i++) {
      sb.append(LOCATOR_ALPHABET.charAt((int) (h % LOCATOR_ALPHABET.length())));
      h /= LOCATOR_ALPHABET.length();
    }
    return sb.toString();
  }

  private static String ticket(String externalOrderId, Passenger p) {
    return "0067"
        + String.format(
            "%09d",
            Math.floorMod((long) (externalOrderId + p.getEmail()).hashCode(), 1_000_000_000L));
  }
}
