package io.travelos.supplier.sandbox;

import io.travelos.common.ids.IdPrefix;
import io.travelos.common.ids.Ids;
import io.travelos.contracts.common.v1.Cabin;
import io.travelos.contracts.common.v1.TimeWindow;
import io.travelos.contracts.offer.v1.Offer;
import io.travelos.contracts.offer.v1.OfferType;
import io.travelos.contracts.supplier.v1.BookingStatus;
import io.travelos.contracts.supplier.v1.CancelOrderRequest;
import io.travelos.contracts.supplier.v1.CancelOrderResponse;
import io.travelos.contracts.supplier.v1.ChangeOrderRequest;
import io.travelos.contracts.supplier.v1.ChangeOrderResponse;
import io.travelos.contracts.supplier.v1.CreateOrderRequest;
import io.travelos.contracts.supplier.v1.CreateOrderResponse;
import io.travelos.contracts.supplier.v1.GetBookingStatusRequest;
import io.travelos.contracts.supplier.v1.Passenger;
import io.travelos.contracts.supplier.v1.PriceOfferRequest;
import io.travelos.contracts.supplier.v1.PriceOfferResponse;
import io.travelos.contracts.supplier.v1.SearchAirRequest;
import io.travelos.contracts.supplier.v1.SearchAirResponse;
import io.travelos.contracts.supplier.v1.SupplierCapabilities;
import io.travelos.contracts.supplier.v1.SupplierOrderStatus;
import io.travelos.supplier.AirSupplier;
import io.travelos.supplier.notification.SupplierNotification;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * A supplier that behaves like a well-mannered airline API: deterministic inventory, offers that
 * expire, prices that must be confirmed before booking, orders that are idempotent by the caller's
 * key, and documented ways to make it fail.
 */
@Component
public class SandboxAirSupplier implements AirSupplier {

  private static final String LOCATOR_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final SandboxOrderRepository orders;
  private final SandboxDisruptionRepository disruptions;
  private final Clock clock;

  public SandboxAirSupplier(
      SandboxOrderRepository orders, SandboxDisruptionRepository disruptions, Clock clock) {
    this.orders = orders;
    this.disruptions = disruptions;
    this.clock = clock;
  }

  @Override
  public SupplierCapabilities capabilities() {
    return SupplierCapabilities.newBuilder()
        .setProvider(provider())
        .addTypes(OfferType.AIR)
        .setChangeSupported(true)
        .setCancelSupported(true)
        .setStatusLookupSupported(true)
        .setNotificationsSupported(true)
        .setIntegration("SIMULATED")
        .build();
  }

  /** The airline's own ledger answers by our key or by its order id. */
  @Override
  public BookingStatus bookingStatus(GetBookingStatusRequest request) {
    String tenant = request.getCtx().getTenantId();
    Optional<SandboxOrderRepository.SandboxOrder> order =
        request.getExternalOrderId().isBlank()
            ? orders.byIdempotencyKey(tenant, request.getIdempotencyKey())
            : orders.byId(request.getExternalOrderId()).filter(o -> o.tenantId().equals(tenant));
    if (order.isEmpty()) {
      return BookingStatus.newBuilder().setStatus(SupplierOrderStatus.NOT_FOUND).build();
    }
    SandboxOrderRepository.SandboxOrder o = order.get();
    return BookingStatus.newBuilder()
        .setStatus(
            switch (o.status()) {
              case "CANCELLED" -> SupplierOrderStatus.CANCELLED;
              case "CHANGED" -> SupplierOrderStatus.CHANGED;
              default -> SupplierOrderStatus.CONFIRMED;
            })
        .setExternalOrderId(o.externalOrderId())
        .setRecordLocator(o.recordLocator())
        .setCharged(SandboxInventory.usd(o.chargedMinor()))
        .addAllTicketNumbers(o.ticketNumbers())
        .build();
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
    // The window may span several UTC dates (an overnight deadline, a recovery that must consider
    // tomorrow): every date in it is searched, bounded, and each offer carries its own date.
    LocalDate lastDate =
        request.getOutboundDeparture().hasNotAfter()
            ? date(request.getOutboundDeparture().getNotAfter().getSeconds())
            : outboundDate;
    if (lastDate.isAfter(outboundDate.plusDays(MAX_SEARCH_DAYS - 1))) {
      lastDate = outboundDate.plusDays(MAX_SEARCH_DAYS - 1);
    }
    for (LocalDate date = outboundDate; !date.isAfter(lastDate); date = date.plusDays(1)) {
      List<SandboxInventory.Schedule> schedules = new ArrayList<>();
      for (Cabin cabin : cabins) {
        schedules.addAll(
            schedulesOn(
                request.getCtx(),
                request.getOrigin(),
                request.getDestination(),
                date,
                inboundDate,
                cabin));
      }
      for (SandboxInventory.Schedule s : schedules) {
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
                date,
                inboundDate,
                now,
                session,
                Ids.newId(IdPrefix.OFFER)));
      }
    }
    return response.build();
  }

  /**
   * What the airline sells on one date for one caller: the date's own cancellation applied (the
   * cancelled flight gone; for the disrupted trip, reaccommodation fares, or nothing at all when it
   * was moved to the next day) and the previous date's next-day move spilling in (for the disrupted
   * trip every flight carries the reaccommodation fares; everyone else sees published fares).
   */
  private List<SandboxInventory.Schedule> schedulesOn(
      io.travelos.contracts.common.v1.RequestContext ctx,
      String origin,
      String destination,
      LocalDate date,
      @Nullable LocalDate inboundDate,
      Cabin cabin) {
    String tenant = ctx.getTenantId();
    Optional<SandboxReaccommodation> today =
        reaccommodation(tenant, origin, destination, date, cabin);
    Optional<SandboxReaccommodation> spill =
        reaccommodation(tenant, origin, destination, date.minusDays(1), cabin)
            .filter(SandboxReaccommodation::nextDay)
            .filter(r -> r.repricesFor(ctx.getCorrelationId()));
    if (spill.isPresent() && today.isEmpty()) {
      return SandboxInventory.schedules(
          origin, destination, date, inboundDate, EnumSet.of(cabin), spill.get(), true, true);
    }
    return SandboxInventory.schedules(
        origin,
        destination,
        date,
        inboundDate,
        EnumSet.of(cabin),
        today.orElse(null),
        today.map(o -> o.repricesFor(ctx.getCorrelationId())).orElse(false));
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
    SandboxInventory.Schedule schedule = schedule(request.getCtx(), id);
    // The repriced offer keeps the searched offer's issue time: the provider offer id encodes it,
    // and pricing an offer must hand back the same id (a fresh instant made the id differ whenever
    // a second boundary fell between the search and the price call).
    Offer offer =
        SandboxInventory.toOffer(
            schedule,
            id.origin(),
            id.destination(),
            id.outboundDate(),
            id.inboundDate(),
            Instant.ofEpochSecond(id.issuedEpochSeconds()),
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
    SandboxInventory.Schedule schedule = schedule(request.getCtx(), id);
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

  /**
   * Reissue the order onto a new itinerary. Idempotent by the caller's key: a retry after a crash
   * (ours or theirs) returns the reissue already made and charges nothing twice.
   */
  @Override
  @Transactional
  public ChangeOrderResponse changeOrder(ChangeOrderRequest request) {
    String key = request.getCtx().getIdempotencyKey();
    if (key.isBlank()) {
      throw new SupplierException(
          "IDEMPOTENCY_KEY_REQUIRED", "ChangeOrder requires ctx.idempotency_key", false);
    }
    String tenant = request.getCtx().getTenantId();
    SandboxOrderRepository.SandboxOrder order = orderOf(tenant, request.getExternalOrderId());
    Optional<SandboxDisruptionRepository.Change> existing =
        disruptions.changeByIdempotencyKey(tenant, key);
    if (existing.isPresent()) {
      return changeResponse(order, existing.get());
    }
    if ("CANCELLED".equals(order.status())) {
      throw new SupplierException(
          "ORDER_CANCELLED", "order " + order.externalOrderId() + " is cancelled", false);
    }
    if (request.getNewProviderOfferId().isBlank()) {
      throw new SupplierException(
          "OFFER_REQUIRED", "ChangeOrder requires new_provider_offer_id", false);
    }
    SandboxOfferId next = SandboxOfferId.decode(request.getNewProviderOfferId());
    Instant now = clock.instant();
    if (Instant.ofEpochSecond(next.issuedEpochSeconds())
        .plus(SandboxInventory.OFFER_TTL)
        .isBefore(now)) {
      throw new SupplierException("OFFER_EXPIRED", "offer expired; search again", false);
    }
    if (next.slot() == SandboxInventory.FAILING_SLOT) {
      throw new SupplierException(
          "SEAT_NO_LONGER_AVAILABLE", "the last seat on this itinerary was just sold", false);
    }
    if ("decline".equals(request.getPaymentToken())) {
      throw new SupplierException("PAYMENT_DECLINED", "payment was declined by the issuer", false);
    }
    SandboxInventory.Schedule schedule = schedule(request.getCtx(), next);
    int passengers = Math.max(1, order.passengers().size());
    long charged = schedule.fareMinor() * passengers;
    String changeId = Ids.newId(IdPrefix.ORDER_CHANGE);
    List<String> tickets = new ArrayList<>();
    for (int i = 0; i < passengers; i++) {
      tickets.add(ticketFor(order.externalOrderId() + ":" + changeId, order.passengers().get(i)));
    }
    SandboxDisruptionRepository.Change change =
        new SandboxDisruptionRepository.Change(
            changeId,
            tenant,
            key,
            order.externalOrderId(),
            order.providerOfferId(),
            request.getNewProviderOfferId(),
            charged - order.chargedMinor(),
            charged,
            tickets,
            now);
    orders.reissue(order.externalOrderId(), request.getNewProviderOfferId(), charged, tickets, now);
    disruptions.insertChange(change);
    return changeResponse(order, change);
  }

  private static ChangeOrderResponse changeResponse(
      SandboxOrderRepository.SandboxOrder order, SandboxDisruptionRepository.Change change) {
    return ChangeOrderResponse.newBuilder()
        .setExternalOrderId(order.externalOrderId())
        .setStatus(SupplierOrderStatus.CHANGED)
        .setIncrementalCost(SandboxInventory.usd(change.incrementalMinor()))
        .setRecordLocator(order.recordLocator())
        .addAllTicketNumbers(change.ticketNumbers())
        .setChargedTotal(SandboxInventory.usd(change.chargedMinor()))
        .build();
  }

  // ---------------------------------------------------------------- notices (the airline speaks)

  /**
   * The sandbox's webhook body -> our notification. Pure; the tenant/trip are filled in by the
   * gateway.
   */
  @Override
  public SupplierNotification normalizeNotification(String rawPayload) {
    SandboxNotice notice = parse(rawPayload);
    SandboxOrderRepository.SandboxOrder order =
        orders
            .byId(notice.externalOrderId())
            .orElseThrow(
                () ->
                    new SupplierException(
                        "ORDER_UNKNOWN", "no such order: " + notice.externalOrderId(), false));
    SandboxOfferId id = SandboxOfferId.decode(order.providerOfferId());
    SandboxInventory.Schedule schedule = baseSchedule(id);
    SandboxInventory.Leg leg = schedule.outbound().getFirst();
    // whether the order is really on the flight the notice names is checked when a NEW notice is
    // applied (applyNotification): a redelivery of an old notice must still normalize to the same
    // supplier event id so the gateway can answer it idempotently after the order has moved on
    SupplierNotification.Type type;
    try {
      type = SupplierNotification.Type.valueOf(notice.type().trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException | NullPointerException e) {
      throw new SupplierException("INVALID_NOTICE", "unknown notice type: " + notice.type(), false);
    }
    SupplierNotification.Severity severity =
        notice.severity() == null || notice.severity().isBlank()
            ? (type == SupplierNotification.Type.FLIGHT_CANCELLED
                ? SupplierNotification.Severity.HIGH
                : SupplierNotification.Severity.MEDIUM)
            : SupplierNotification.Severity.valueOf(
                notice.severity().trim().toUpperCase(Locale.ROOT));
    return new SupplierNotification(
        provider(),
        notice.eventId(),
        order.tenantId(),
        "",
        order.externalOrderId(),
        order.recordLocator(),
        type,
        severity,
        clock.instant(),
        "sandbox-air/notices/" + notice.eventId(),
        notice.reason(),
        new SupplierNotification.AffectedSegment(
            leg.flightNumber() + "-" + leg.origin(),
            leg.carrier(),
            leg.flightNumber(),
            leg.origin(),
            leg.destination(),
            leg.departure(),
            leg.arrival()));
  }

  /** The airline acts on its own notice: the flight is gone and the survivors are repriced. */
  @Override
  public void applyNotification(SupplierNotification notice, String rawPayload) {
    if (notice.type() != SupplierNotification.Type.FLIGHT_CANCELLED) {
      return;
    }
    SandboxNotice raw = parse(rawPayload);
    long delta = raw.reaccommodation() == null ? 0L : raw.reaccommodation().fareDeltaMinor();
    SandboxOrderRepository.SandboxOrder order = orders.byId(notice.externalOrderId()).orElseThrow();
    SandboxOfferId id = SandboxOfferId.decode(order.providerOfferId());
    SandboxInventory.Leg leg = baseSchedule(id).outbound().getFirst();
    if (raw.flightNumber() != null
        && !raw.flightNumber().isBlank()
        && !raw.flightNumber().equalsIgnoreCase(leg.flightNumber())) {
      throw new SupplierException(
          "NOTICE_MISMATCH",
          "order "
              + order.externalOrderId()
              + " is on "
              + leg.flightNumber()
              + ", not "
              + raw.flightNumber(),
          false);
    }
    Cabin cabin = Cabin.valueOf(id.cabin());
    boolean nextDay = raw.reaccommodation() != null && raw.reaccommodation().movesToNextDay();
    disruptions.saveReaccommodation(
        SandboxInventory.reaccommodate(
            order.tenantId(), notice.correlationId(), id, cabin, delta, nextDay),
        clock.instant());
  }

  private static SandboxNotice parse(String rawPayload) {
    SandboxNotice notice;
    try {
      notice = JSON.readValue(rawPayload, SandboxNotice.class);
    } catch (RuntimeException e) {
      throw new SupplierException(
          "INVALID_NOTICE", "unreadable sandbox notice: " + e.getMessage(), false);
    }
    if (notice.eventId() == null || notice.eventId().isBlank()) {
      throw new SupplierException("INVALID_NOTICE", "eventId is required", false);
    }
    if (notice.externalOrderId() == null || notice.externalOrderId().isBlank()) {
      throw new SupplierException("INVALID_NOTICE", "externalOrderId is required", false);
    }
    return notice;
  }

  private SandboxOrderRepository.SandboxOrder orderOf(String tenant, String externalOrderId) {
    SandboxOrderRepository.SandboxOrder order =
        orders
            .byId(externalOrderId)
            .orElseThrow(
                () ->
                    new SupplierException(
                        "ORDER_UNKNOWN", "no such order: " + externalOrderId, false));
    if (!order.tenantId().equals(tenant)) {
      throw new SupplierException("ORDER_UNKNOWN", "no such order: " + externalOrderId, false);
    }
    return order;
  }

  /** A search window is served for at most this many UTC dates (the sandbox has no seasons). */
  static final int MAX_SEARCH_DAYS = 3;

  private Optional<SandboxReaccommodation> reaccommodation(
      String tenant, String origin, String destination, LocalDate outboundDate, Cabin cabin) {
    return disruptions.reaccommodation(tenant, origin, destination, outboundDate);
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
          baseSchedule(id).refundable()
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

  /** The schedule behind an offer id as the airline sells it today (reaccommodation applied). */
  private SandboxInventory.Schedule schedule(
      io.travelos.contracts.common.v1.RequestContext ctx, SandboxOfferId id) {
    Cabin cabin = Cabin.valueOf(id.cabin());
    Optional<SandboxReaccommodation> overlay =
        reaccommodation(ctx.getTenantId(), id.origin(), id.destination(), id.outboundDate(), cabin);
    // A cancelled flight is recognised by its number: slots are numbered per generated schedule
    // and a one-way search numbers them differently from a round trip on the same day.
    if (overlay.isPresent()
        && overlay
            .get()
            .cancelledFlight()
            .equals(baseSchedule(id).outbound().getFirst().flightNumber())) {
      throw new SupplierException(
          "FLIGHT_CANCELLED", "this flight was cancelled by the airline; search again", false);
    }
    return schedulesOn(
            ctx, id.origin(), id.destination(), id.outboundDate(), id.inboundDate(), cabin)
        .stream()
        .filter(s -> s.slot() == id.slot())
        .findFirst()
        .orElseThrow(
            () ->
                new SupplierException(
                    "OFFER_UNKNOWN",
                    "offer slot no longer exists (or the airline has moved this passenger to"
                        + " another date)",
                    false));
  }

  /** The schedule as originally generated, cancellations and repricing ignored. */
  private static SandboxInventory.Schedule baseSchedule(SandboxOfferId id) {
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

  private static String ticketFor(String seed, String passengerName) {
    return "0067"
        + String.format(
            "%09d", Math.floorMod((long) (seed + passengerName).hashCode(), 1_000_000_000L));
  }

  private static String ticket(String externalOrderId, Passenger p) {
    return "0067"
        + String.format(
            "%09d",
            Math.floorMod((long) (externalOrderId + p.getEmail()).hashCode(), 1_000_000_000L));
  }
}
