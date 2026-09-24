package io.travelos.supplier.sandbox;

import io.travelos.common.geo.Locations;
import io.travelos.common.ids.IdPrefix;
import io.travelos.common.ids.Ids;
import io.travelos.contracts.common.v1.RequestContext;
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
import io.travelos.contracts.supplier.v1.QuoteOfferRequest;
import io.travelos.contracts.supplier.v1.QuoteOfferResponse;
import io.travelos.contracts.supplier.v1.SearchGroundRequest;
import io.travelos.contracts.supplier.v1.SearchGroundResponse;
import io.travelos.contracts.supplier.v1.SupplierCapabilities;
import io.travelos.contracts.supplier.v1.SupplierOrderStatus;
import io.travelos.supplier.AirSupplier.SupplierException;
import io.travelos.supplier.GroundSupplier;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * SIMULATED supplier: scheduled ground transfers. Same contract discipline as the hotel sandbox:
 * idempotent bookings, re-timing on the same vendor through ChangeOrder, cancellation terms, status
 * lookup. Faults are fixtures ({@link SandboxGroundInventory}).
 */
@Component
public class SandboxGroundSupplier implements GroundSupplier {
  static final String KIND = "GROUND";
  private static final Set<String> KINDS =
      Set.of("AIRPORT_TO_HOTEL", "HOTEL_TO_AIRPORT", "POINT_TO_POINT");

  private final SandboxBookingRepository bookings;
  private final TransactionTemplate tx;
  private final Clock clock;

  public SandboxGroundSupplier(
      SandboxBookingRepository bookings, TransactionTemplate tx, Clock clock) {
    this.bookings = bookings;
    this.tx = tx;
    this.clock = clock;
  }

  @Override
  public String provider() {
    return SandboxGroundInventory.PROVIDER;
  }

  @Override
  public SupplierCapabilities capabilities() {
    return SupplierCapabilities.newBuilder()
        .setProvider(provider())
        .addTypes(OfferType.GROUND)
        .setChangeSupported(true)
        .setCancelSupported(true)
        .setStatusLookupSupported(true)
        .setNotificationsSupported(false)
        .setIntegration("SIMULATED")
        .setMutationsIdempotent(true)
        .setReconciliationByKeySupported(true)
        .build();
  }

  @Override
  public SearchGroundResponse searchGround(SearchGroundRequest request) {
    String city = request.getCity().trim().toUpperCase();
    if ("ZZZ".equals(city)) {
      throw new SupplierException(
          "UNAVAILABLE", "sandbox-ground is simulating an outage for ZZZ", true);
    }
    ZoneId zone =
        Locations.zoneOf(city)
            .orElseThrow(
                () -> new SupplierException("UNKNOWN_CITY", "no vendors in " + city, false));
    if (!KINDS.contains(request.getKind())) {
      throw new SupplierException("INVALID_KIND", "kind must be one of " + KINDS, false);
    }
    if (!request.hasPickup() || !request.getPickup().hasNotBefore()) {
      throw new SupplierException("INVALID_WINDOW", "pickup.not_before is required", false);
    }
    TimeWindow window = request.getPickup();
    Instant notBefore = Instant.ofEpochSecond(window.getNotBefore().getSeconds());
    Instant notAfter =
        window.hasNotAfter()
            ? Instant.ofEpochSecond(window.getNotAfter().getSeconds())
            : notBefore.plus(Duration.ofHours(3));
    if (notAfter.isBefore(notBefore)) {
      throw new SupplierException("INVALID_WINDOW", "pickup.not_after precedes not_before", false);
    }
    Instant now = clock.instant();
    String session = Ids.newId(IdPrefix.SEARCH_SESSION);
    SearchGroundResponse.Builder response =
        SearchGroundResponse.newBuilder().setSearchSessionId(session);
    String from =
        request.getFromLocation().isBlank() ? city + " airport" : request.getFromLocation();
    String to = request.getToLocation().isBlank() ? "hotel" : request.getToLocation();
    for (SandboxGroundInventory.Vendor v : SandboxGroundInventory.catalog(city)) {
      Instant pickup = quarterHour(notBefore);
      for (int slot = 0;
          slot < SandboxGroundInventory.MAX_PICKUPS && !pickup.isAfter(notAfter);
          slot++, pickup = pickup.plus(SandboxGroundInventory.PICKUP_STEP)) {
        SandboxTransferId id =
            new SandboxTransferId(
                city,
                request.getKind(),
                from,
                to,
                pickup.getEpochSecond(),
                v.code(),
                now.getEpochSecond());
        response.addOffers(
            SandboxGroundInventory.toOffer(v, id, zone, now, session, Ids.newId(IdPrefix.OFFER)));
      }
    }
    return response.build();
  }

  @Override
  public QuoteOfferResponse quote(QuoteOfferRequest request) {
    SandboxTransferId id = SandboxTransferId.decode(request.getProviderOfferId());
    SandboxGroundInventory.Vendor v = vendor(id);
    Instant now = clock.instant();
    boolean expired = expired(id, now);
    SandboxTransferId current =
        expired
            ? new SandboxTransferId(
                id.city(),
                id.kind(),
                id.from(),
                id.to(),
                id.pickupEpochSeconds(),
                id.vendorCode(),
                now.getEpochSecond())
            : id;
    Offer offer =
        SandboxGroundInventory.toOffer(v, current, zone(id), now, "", Ids.newId(IdPrefix.OFFER));
    return QuoteOfferResponse.newBuilder()
        .setOffer(offer)
        .setPriceChanged(false)
        .setRequoted(expired)
        .build();
  }

  @Override
  public CreateOrderResponse createOrder(CreateOrderRequest request) {
    String key = requireKey(request.getCtx(), "CreateOrder");
    String tenant = request.getCtx().getTenantId();
    Optional<SandboxBookingRepository.Booking> existing = bookings.byIdempotencyKey(tenant, key);
    if (existing.isPresent()) {
      return response(existing.get());
    }
    SandboxTransferId id = SandboxTransferId.decode(request.getProviderOfferId());
    SandboxGroundInventory.Vendor v = vendor(id);
    Instant now = clock.instant();
    if (expired(id, now)) {
      throw new SupplierException("OFFER_EXPIRED", "quote expired; re-quote first", false);
    }
    if (v.fault() == SandboxFault.FAIL) {
      throw new SupplierException(
          "VEHICLE_NO_LONGER_AVAILABLE", v.name() + " has no vehicle at that time", false);
    }
    if ("decline".equals(request.getPaymentToken())) {
      throw new SupplierException("PAYMENT_DECLINED", "payment was declined by the issuer", false);
    }
    if (request.getPassengersCount() == 0) {
      throw new SupplierException(
          "PASSENGER_REQUIRED", "at least one passenger is required", false);
    }
    String bookingId = SandboxTransferId.PREFIX + Ids.newId(IdPrefix.ORDER).substring(4);
    List<String> passengers =
        request.getPassengersList().stream()
            .map(g -> g.getGivenName() + " " + g.getFamilyName())
            .toList();
    SandboxBookingRepository.Booking booking =
        new SandboxBookingRepository.Booking(
            bookingId,
            KIND,
            tenant,
            key,
            request.getProviderOfferId(),
            SandboxCodes.confirmation(bookingId),
            "CONFIRMED",
            "USD",
            v.fareMinor(),
            v.refundable() ? 0 : v.fareMinor(),
            passengers,
            now,
            now);
    tx.executeWithoutResult(s -> bookings.insert(booking));
    if (v.fault() == SandboxFault.TIMEOUT) {
      throw new SupplierException(
          "TIMEOUT", v.name() + " confirmed the transfer but the answer was lost", true);
    }
    return response(booking);
  }

  @Override
  public ChangeOrderResponse changeOrder(ChangeOrderRequest request) {
    String key = requireKey(request.getCtx(), "ChangeOrder");
    String tenant = request.getCtx().getTenantId();
    SandboxBookingRepository.Booking booking = bookingOf(tenant, request.getExternalOrderId());
    Optional<SandboxBookingRepository.Change> existing =
        bookings.changeByIdempotencyKey(tenant, key);
    if (existing.isPresent()) {
      return changeResponse(booking, existing.get());
    }
    if ("CANCELLED".equals(booking.status())) {
      throw new SupplierException(
          "ORDER_CANCELLED", "booking " + booking.bookingId() + " is cancelled", false);
    }
    SandboxTransferId previous = SandboxTransferId.decode(booking.providerOfferId());
    SandboxTransferId next = SandboxTransferId.decode(request.getNewProviderOfferId());
    if (!next.city().equals(previous.city()) || !next.vendorCode().equals(previous.vendorCode())) {
      throw new SupplierException(
          "CHANGE_NOT_SUPPORTED",
          "sandbox-ground re-times a transfer with the same vendor only; cancel and rebook to switch",
          false);
    }
    SandboxGroundInventory.Vendor v = vendor(next);
    Instant now = clock.instant();
    if (expired(next, now)) {
      throw new SupplierException("OFFER_EXPIRED", "quote expired; re-quote first", false);
    }
    long charged = v.fareMinor();
    SandboxBookingRepository.Change change =
        new SandboxBookingRepository.Change(
            Ids.newId(IdPrefix.ORDER_CHANGE),
            tenant,
            key,
            booking.bookingId(),
            booking.providerOfferId(),
            request.getNewProviderOfferId(),
            charged - booking.chargedMinor(),
            charged,
            now);
    tx.executeWithoutResult(
        s -> {
          bookings.reissue(booking.bookingId(), request.getNewProviderOfferId(), charged, now);
          bookings.insertChange(change);
        });
    return changeResponse(booking, change);
  }

  @Override
  public CancelOrderResponse cancelOrder(CancelOrderRequest request) {
    String tenant = request.getCtx().getTenantId();
    SandboxBookingRepository.Booking booking = bookingOf(tenant, request.getExternalOrderId());
    long refund = booking.chargedMinor() - booking.penaltyMinor();
    if (!"CANCELLED".equals(booking.status())) {
      bookings.updateStatus(
          booking.bookingId(), "CANCELLED", booking.penaltyMinor(), clock.instant());
    } else {
      refund = 0;
    }
    return CancelOrderResponse.newBuilder()
        .setExternalOrderId(booking.bookingId())
        .setStatus(SupplierOrderStatus.CANCELLED)
        .setRefund(SandboxHotelInventory.money(booking.currency(), Math.max(0, refund)))
        .build();
  }

  @Override
  public BookingStatus bookingStatus(GetBookingStatusRequest request) {
    String tenant = request.getCtx().getTenantId();
    Optional<SandboxBookingRepository.Booking> booking =
        request.getExternalOrderId().isBlank()
            ? bookings.byIdempotencyKey(tenant, request.getIdempotencyKey())
            : bookings.byId(tenant, request.getExternalOrderId());
    if (booking.isEmpty() || !KIND.equals(booking.get().kind())) {
      return BookingStatus.newBuilder().setStatus(SupplierOrderStatus.NOT_FOUND).build();
    }
    SandboxBookingRepository.Booking b = booking.get();
    return BookingStatus.newBuilder()
        .setStatus(status(b))
        .setExternalOrderId(b.bookingId())
        .setRecordLocator(b.confirmation())
        .setCharged(SandboxHotelInventory.money(b.currency(), b.chargedMinor()))
        .build();
  }

  // ------------------------------------------------------------------ helpers

  private static Instant quarterHour(Instant at) {
    long q = 15 * 60;
    return Instant.ofEpochSecond(((at.getEpochSecond() + q - 1) / q) * q);
  }

  private static SupplierOrderStatus status(SandboxBookingRepository.Booking b) {
    return switch (b.status()) {
      case "CANCELLED" -> SupplierOrderStatus.CANCELLED;
      case "CHANGED" -> SupplierOrderStatus.CHANGED;
      default -> SupplierOrderStatus.CONFIRMED;
    };
  }

  private static CreateOrderResponse response(SandboxBookingRepository.Booking b) {
    return CreateOrderResponse.newBuilder()
        .setExternalOrderId(b.bookingId())
        .setRecordLocator(b.confirmation())
        .setStatus(status(b))
        .setCharged(SandboxHotelInventory.money(b.currency(), b.chargedMinor()))
        .build();
  }

  private static ChangeOrderResponse changeResponse(
      SandboxBookingRepository.Booking b, SandboxBookingRepository.Change c) {
    return ChangeOrderResponse.newBuilder()
        .setExternalOrderId(b.bookingId())
        .setStatus(SupplierOrderStatus.CHANGED)
        .setIncrementalCost(SandboxHotelInventory.money(b.currency(), c.incrementalMinor()))
        .setRecordLocator(b.confirmation())
        .setChargedTotal(SandboxHotelInventory.money(b.currency(), c.chargedMinor()))
        .build();
  }

  private SandboxBookingRepository.Booking bookingOf(String tenant, String bookingId) {
    return bookings
        .byId(tenant, bookingId)
        .filter(b -> KIND.equals(b.kind()))
        .orElseThrow(
            () -> new SupplierException("ORDER_UNKNOWN", "no such booking: " + bookingId, false));
  }

  private static SandboxGroundInventory.Vendor vendor(SandboxTransferId id) {
    return SandboxGroundInventory.vendor(id.city(), id.vendorCode())
        .orElseThrow(
            () ->
                new SupplierException(
                    "OFFER_UNKNOWN", "no vendor " + id.vendorCode() + " in " + id.city(), false));
  }

  private static ZoneId zone(SandboxTransferId id) {
    return Locations.zoneOf(id.city())
        .orElseThrow(
            () -> new SupplierException("UNKNOWN_CITY", "unknown city " + id.city(), false));
  }

  private static boolean expired(SandboxTransferId id, Instant now) {
    return Instant.ofEpochSecond(id.issuedEpochSeconds())
        .plus(SandboxGroundInventory.OFFER_TTL)
        .isBefore(now);
  }

  private static String requireKey(RequestContext ctx, String op) {
    if (ctx.getIdempotencyKey().isBlank()) {
      throw new SupplierException(
          "IDEMPOTENCY_KEY_REQUIRED", op + " requires ctx.idempotency_key", false);
    }
    return ctx.getIdempotencyKey();
  }
}
