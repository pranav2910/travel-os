package io.travelos.supplier.sandbox;

import io.travelos.common.geo.Locations;
import io.travelos.common.ids.IdPrefix;
import io.travelos.common.ids.Ids;
import io.travelos.contracts.common.v1.RequestContext;
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
import io.travelos.contracts.supplier.v1.SearchHotelsRequest;
import io.travelos.contracts.supplier.v1.SearchHotelsResponse;
import io.travelos.contracts.supplier.v1.SupplierCapabilities;
import io.travelos.contracts.supplier.v1.SupplierOrderStatus;
import io.travelos.supplier.AirSupplier.SupplierException;
import io.travelos.supplier.HotelSupplier;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * SIMULATED supplier: a hotel chain with deterministic catalogs per city, real supplier-side
 * idempotency, cancellation terms, changes of dates on the same property, and status lookup. Its
 * faults are fixtures ({@link SandboxHotelInventory}); a live adapter replaces this class and
 * nothing above the gateway changes.
 */
@Component
public class SandboxHotelSupplier implements HotelSupplier {
  static final String KIND = "HOTEL";

  private final SandboxBookingRepository bookings;
  private final TransactionTemplate tx;
  private final Clock clock;

  public SandboxHotelSupplier(
      SandboxBookingRepository bookings, TransactionTemplate tx, Clock clock) {
    this.bookings = bookings;
    this.tx = tx;
    this.clock = clock;
  }

  @Override
  public String provider() {
    return SandboxHotelInventory.PROVIDER;
  }

  @Override
  public SupplierCapabilities capabilities() {
    return SupplierCapabilities.newBuilder()
        .setProvider(provider())
        .addTypes(OfferType.HOTEL)
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
  public SearchHotelsResponse searchHotels(SearchHotelsRequest request) {
    String city = request.getCity().trim().toUpperCase();
    if ("ZZZ".equals(city)) {
      throw new SupplierException(
          "UNAVAILABLE", "sandbox-hotel is simulating an outage for ZZZ", true);
    }
    ZoneId zone =
        Locations.zoneOf(city)
            .orElseThrow(
                () -> new SupplierException("UNKNOWN_CITY", "no properties in " + city, false));
    LocalDate in = date(request.getCheckInDate(), "check_in_date");
    LocalDate out = date(request.getCheckOutDate(), "check_out_date");
    if (!out.isAfter(in)) {
      throw new SupplierException(
          "INVALID_DATES", "check_out_date must be after check_in_date", false);
    }
    if (request.getGuests() < 1) {
      throw new SupplierException("GUESTS_REQUIRED", "guests must be at least 1", false);
    }
    Instant now = clock.instant();
    String session = Ids.newId(IdPrefix.SEARCH_SESSION);
    SearchHotelsResponse.Builder response =
        SearchHotelsResponse.newBuilder().setSearchSessionId(session);
    for (SandboxHotelInventory.Property p : SandboxHotelInventory.catalog(city)) {
      SandboxStayId id = new SandboxStayId(city, in, out, p.code(), now.getEpochSecond());
      response.addOffers(
          SandboxHotelInventory.toOffer(
              p, id, zone, p.nightlyMinor(), now, session, Ids.newId(IdPrefix.OFFER)));
    }
    return response.build();
  }

  @Override
  public QuoteOfferResponse quote(QuoteOfferRequest request) {
    SandboxStayId id = SandboxStayId.decode(request.getProviderOfferId());
    SandboxHotelInventory.Property p = property(id);
    Instant now = clock.instant();
    boolean expired = expired(id, p, now);
    SandboxStayId current =
        expired
            ? new SandboxStayId(
                id.city(), id.checkIn(), id.checkOut(), id.propertyCode(), now.getEpochSecond())
            : id;
    long nightly = SandboxHotelInventory.nightlyOnQuote(p);
    Offer offer =
        SandboxHotelInventory.toOffer(
            p, current, zone(id), nightly, now, "", Ids.newId(IdPrefix.OFFER));
    return QuoteOfferResponse.newBuilder()
        .setOffer(offer)
        .setPriceChanged(nightly != p.nightlyMinor())
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
    SandboxStayId id = SandboxStayId.decode(request.getProviderOfferId());
    SandboxHotelInventory.Property p = property(id);
    Instant now = clock.instant();
    if (expired(id, p, now)) {
      throw new SupplierException("OFFER_EXPIRED", "quote expired; re-quote first", false);
    }
    if (p.fault() == SandboxFault.FAIL) {
      throw new SupplierException(
          "ROOM_NO_LONGER_AVAILABLE", "the last room at " + p.name() + " was just sold", false);
    }
    if ("decline".equals(request.getPaymentToken())) {
      throw new SupplierException("PAYMENT_DECLINED", "payment was declined by the issuer", false);
    }
    if (request.getPassengersCount() == 0) {
      throw new SupplierException("GUEST_REQUIRED", "at least one guest is required", false);
    }
    long charged =
        SandboxHotelInventory.nightlyOnQuote(p) * Locations.nights(id.checkIn(), id.checkOut());
    String bookingId = SandboxStayId.PREFIX + Ids.newId(IdPrefix.ORDER).substring(4);
    List<String> guests =
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
            p.currency(),
            charged,
            p.refundable() ? 0 : charged,
            guests,
            now,
            now);
    // Committed on its own: the TIMEOUT fixture loses the answer AFTER the supplier has booked,
    // which is exactly the case reconciliation exists for.
    tx.executeWithoutResult(s -> bookings.insert(booking));
    if (p.fault() == SandboxFault.TIMEOUT) {
      throw new SupplierException(
          "TIMEOUT", p.name() + " confirmed the room but the answer was lost", true);
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
    SandboxStayId previous = SandboxStayId.decode(booking.providerOfferId());
    SandboxStayId next = SandboxStayId.decode(request.getNewProviderOfferId());
    if (!next.city().equals(previous.city())
        || !next.propertyCode().equals(previous.propertyCode())) {
      throw new SupplierException(
          "CHANGE_NOT_SUPPORTED",
          "sandbox-hotel changes dates on the same property only; cancel and rebook to move",
          false);
    }
    SandboxHotelInventory.Property p = property(next);
    Instant now = clock.instant();
    if (expired(next, p, now)) {
      throw new SupplierException("OFFER_EXPIRED", "quote expired; re-quote first", false);
    }
    long charged =
        SandboxHotelInventory.nightlyOnQuote(p) * Locations.nights(next.checkIn(), next.checkOut());
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
    SandboxStayId id = SandboxStayId.decode(booking.providerOfferId());
    SandboxHotelInventory.Property p = property(id);
    long refund = booking.chargedMinor() - booking.penaltyMinor();
    if (!"CANCELLED".equals(booking.status())) {
      if (p.fault() == SandboxFault.NO_CANCEL) {
        throw new SupplierException(
            "CANCELLATION_REFUSED",
            p.name() + " does not accept cancellations on this rate",
            false);
      }
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

  private static SandboxHotelInventory.Property property(SandboxStayId id) {
    return SandboxHotelInventory.property(id.city(), id.propertyCode())
        .orElseThrow(
            () ->
                new SupplierException(
                    "OFFER_UNKNOWN",
                    "no property " + id.propertyCode() + " in " + id.city(),
                    false));
  }

  private static ZoneId zone(SandboxStayId id) {
    return Locations.zoneOf(id.city())
        .orElseThrow(
            () -> new SupplierException("UNKNOWN_CITY", "unknown city " + id.city(), false));
  }

  private static boolean expired(SandboxStayId id, SandboxHotelInventory.Property p, Instant now) {
    return Instant.ofEpochSecond(id.issuedEpochSeconds())
        .plus(SandboxHotelInventory.ttl(p))
        .isBefore(now);
  }

  private static LocalDate date(String value, String field) {
    try {
      return LocalDate.parse(value);
    } catch (DateTimeParseException | NullPointerException e) {
      throw new SupplierException("INVALID_DATES", field + " must be an ISO date", false);
    }
  }

  private static String requireKey(RequestContext ctx, String op) {
    if (ctx.getIdempotencyKey().isBlank()) {
      throw new SupplierException(
          "IDEMPOTENCY_KEY_REQUIRED", op + " requires ctx.idempotency_key", false);
    }
    return ctx.getIdempotencyKey();
  }
}
