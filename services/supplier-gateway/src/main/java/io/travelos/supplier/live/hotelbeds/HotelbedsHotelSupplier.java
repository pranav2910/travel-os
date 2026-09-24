package io.travelos.supplier.live.hotelbeds;

import com.google.protobuf.Timestamp;
import io.travelos.common.geo.Locations;
import io.travelos.common.ids.IdPrefix;
import io.travelos.common.ids.Ids;
import io.travelos.contracts.common.v1.Money;
import io.travelos.contracts.offer.v1.CancellationTerms;
import io.travelos.contracts.offer.v1.HotelOffer;
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
import io.travelos.contracts.supplier.v1.QuoteOfferRequest;
import io.travelos.contracts.supplier.v1.QuoteOfferResponse;
import io.travelos.contracts.supplier.v1.SearchHotelsRequest;
import io.travelos.contracts.supplier.v1.SearchHotelsResponse;
import io.travelos.contracts.supplier.v1.SupplierCapabilities;
import io.travelos.contracts.supplier.v1.SupplierOrderStatus;
import io.travelos.supplier.AirSupplier.SupplierException;
import io.travelos.supplier.HotelSupplier;
import io.travelos.supplier.live.LiveHttp;
import io.travelos.supplier.live.LiveIntegrationProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Hotelbeds APItude as a hotel supplier (Phase 4, ADR-0016). LIVE integration against the endpoint
 * configured ({@code api.test.hotelbeds.com} books test inventory; the production host books real
 * rooms). Searches by the coordinates of the requested city's airport from the location catalog.
 *
 * <p>Hotelbeds keeps our {@code clientReference} on the booking and lets us list bookings by it, so
 * a lost answer is reconciled by our key instead of being reported as unknown.
 */
public class HotelbedsHotelSupplier implements HotelSupplier {
  private static final Logger log = LoggerFactory.getLogger(HotelbedsHotelSupplier.class);
  static final String PROVIDER = HotelbedsClient.PROVIDER;

  /** Rate keys are short-lived; the offer says so instead of pretending to hold a price. */
  public static final Duration RATE_KEY_LIFETIME = Duration.ofMinutes(20);

  private final HotelbedsClient client;
  private final LiveIntegrationProperties.Hotelbeds props;
  private final Clock clock;

  public HotelbedsHotelSupplier(
      HotelbedsClient client, LiveIntegrationProperties.Hotelbeds props, Clock clock) {
    this.client = client;
    this.props = props;
    this.clock = clock;
  }

  @Override
  public String provider() {
    return PROVIDER;
  }

  @Override
  public SupplierCapabilities capabilities() {
    return SupplierCapabilities.newBuilder()
        .setProvider(PROVIDER)
        .addTypes(OfferType.HOTEL)
        .setChangeSupported(false)
        .setCancelSupported(true)
        .setStatusLookupSupported(true)
        .setNotificationsSupported(false)
        .setIntegration("LIVE")
        .setMutationsIdempotent(false)
        .setReconciliationByKeySupported(true)
        .setNegotiatedRatesSupported(false)
        .build();
  }

  // ------------------------------------------------------------------ search

  @Override
  public SearchHotelsResponse searchHotels(SearchHotelsRequest request) {
    Locations.Place place =
        Locations.place(request.getCity())
            .orElseThrow(
                () ->
                    new SupplierException(
                        "UNKNOWN_LOCATION",
                        "no coordinates for " + request.getCity() + " in the catalog",
                        false));
    ZoneId zone = Locations.zoneOf(request.getCity()).orElseThrow();
    LocalDate checkIn = LocalDate.parse(request.getCheckInDate());
    LocalDate checkOut = LocalDate.parse(request.getCheckOutDate());
    int nights = Locations.nights(checkIn, checkOut);
    ObjectNode body = LiveHttp.JSON.createObjectNode();
    ObjectNode stay = body.putObject("stay");
    stay.put("checkIn", checkIn.toString());
    stay.put("checkOut", checkOut.toString());
    ObjectNode occupancy = body.putArray("occupancies").addObject();
    occupancy.put("rooms", 1);
    occupancy.put("adults", Math.max(1, request.getGuests()));
    occupancy.put("children", 0);
    ObjectNode geo = body.putObject("geolocation");
    geo.put("latitude", place.latitude());
    geo.put("longitude", place.longitude());
    geo.put("radius", props.radiusKm());
    geo.put("unit", "km");
    JsonNode response = client.post("/hotel-api/1.0/hotels", body);
    String session = Ids.newId(IdPrefix.SEARCH_SESSION);
    SearchHotelsResponse.Builder out =
        SearchHotelsResponse.newBuilder().setSearchSessionId(session);
    Instant now = clock.instant();
    for (JsonNode hotel : response.path("hotels").path("hotels")) {
      for (JsonNode room : hotel.path("rooms")) {
        for (JsonNode rate : room.path("rates")) {
          if (!"BOOKABLE".equals(rate.path("rateType").asString("BOOKABLE"))) {
            continue; // RECHECK rates need a checkrates round trip; the quote step does that
          }
          try {
            out.addOffers(
                offer(
                    hotel,
                    room,
                    rate,
                    session,
                    checkIn,
                    checkOut,
                    nights,
                    zone,
                    request.getCity(),
                    now));
          } catch (RuntimeException e) {
            log.warn(
                "hotelbeds rate {} skipped: {}", rate.path("rateKey").asString(), e.getMessage());
          }
        }
      }
    }
    return out.build();
  }

  Offer offer(
      JsonNode hotel,
      JsonNode room,
      JsonNode rate,
      String session,
      LocalDate checkIn,
      LocalDate checkOut,
      int nights,
      ZoneId zone,
      String city,
      Instant now) {
    String currency = hotel.path("currency").asString("EUR");
    long total = LiveHttp.minor(rate.path("net").asString());
    Money money = Money.newBuilder().setCurrency(currency).setAmountMinor(total).build();
    HotelOffer.Builder h =
        HotelOffer.newBuilder()
            .setPropertyId(hotel.path("code").asString())
            .setName(hotel.path("name").asString())
            .setAddress(hotel.path("destinationName").asString(""))
            .setLatitude(hotel.path("latitude").asDouble(0))
            .setLongitude(hotel.path("longitude").asDouble(0))
            .setCheckIn(ts(Locations.checkIn(checkIn, zone)))
            .setCheckOut(ts(Locations.checkOut(checkOut, zone)))
            .setNightlyRate(
                Money.newBuilder()
                    .setCurrency(currency)
                    .setAmountMinor(Math.max(1, nights) == 0 ? total : total / Math.max(1, nights)))
            .setNights(nights)
            .setRoomType(room.path("name").asString(room.path("code").asString("")))
            .setTimeZone(zone.getId())
            .setCity(city)
            .setCheckInDate(checkIn.toString())
            .setCheckOutDate(checkOut.toString());
    CancellationTerms.Builder terms = CancellationTerms.newBuilder();
    JsonNode policies = rate.path("cancellationPolicies");
    boolean refundable = false;
    if (policies.isArray() && !policies.isEmpty()) {
      JsonNode first = policies.get(0);
      Instant from = parseInstant(first.path("from").asString());
      if (from != null && from.isAfter(now)) {
        refundable = true;
        terms.setFreeUntil(ts(from));
      }
      terms.setPenalty(
          Money.newBuilder()
              .setCurrency(currency)
              .setAmountMinor(LiveHttp.minor(first.path("amount").asString("0"))));
    } else {
      terms.setPenalty(money);
    }
    terms.setRefundable(refundable);
    return Offer.newBuilder()
        .setOfferId(Ids.newId(IdPrefix.OFFER))
        .setSearchSessionId(session)
        .setProvider(PROVIDER)
        .setProviderOfferId(rate.path("rateKey").asString())
        .setType(OfferType.HOTEL)
        .setTotal(money)
        .setRefundable(refundable)
        .setExpiresAt(ts(now.plus(RATE_KEY_LIFETIME)))
        .setHotel(h)
        .setCancellation(terms)
        .setRateCode(rate.path("rateClass").asString(""))
        .setNegotiated(false)
        .build();
  }

  // ------------------------------------------------------------------ quote

  @Override
  public QuoteOfferResponse quote(QuoteOfferRequest request) {
    ObjectNode body = LiveHttp.JSON.createObjectNode();
    body.putArray("rooms").addObject().put("rateKey", request.getProviderOfferId());
    JsonNode response = client.post("/hotel-api/1.0/checkrates", body);
    JsonNode hotel = response.path("hotel");
    if (hotel.isMissingNode()) {
      throw new SupplierException(
          "ROOM_NO_LONGER_AVAILABLE", "hotelbeds could not re-check the rate", false);
    }
    RateKey key = RateKey.parse(request.getProviderOfferId());
    String city = ""; // unknown from the key alone; the caller keeps its own city
    ZoneId zone = ZoneId.of("UTC");
    JsonNode room = hotel.path("rooms").get(0);
    JsonNode rate = room.path("rates").get(0);
    Instant now = clock.instant();
    Offer offer =
        offer(
            hotel,
            room,
            rate,
            "",
            key.checkIn(),
            key.checkOut(),
            Locations.nights(key.checkIn(), key.checkOut()),
            zone,
            city,
            now);
    return QuoteOfferResponse.newBuilder()
        .setOffer(offer)
        .setPriceChanged(false)
        .setRequoted(!rate.path("rateKey").asString().equals(request.getProviderOfferId()))
        .build();
  }

  // ------------------------------------------------------------------ booking

  @Override
  public CreateOrderResponse createOrder(CreateOrderRequest request) {
    if (request.getPassengersCount() == 0) {
      throw new SupplierException("PASSENGER_REQUIRED", "a hotel booking needs a holder", false);
    }
    Passenger holder = request.getPassengers(0);
    ObjectNode body = LiveHttp.JSON.createObjectNode();
    ObjectNode h = body.putObject("holder");
    h.put("name", holder.getGivenName());
    h.put("surname", holder.getFamilyName());
    ObjectNode room = body.putArray("rooms").addObject();
    room.put("rateKey", request.getProviderOfferId());
    var paxes = room.putArray("paxes");
    int roomId = 1;
    for (Passenger p : request.getPassengersList()) {
      ObjectNode pax = paxes.addObject();
      pax.put("roomId", roomId);
      pax.put("type", "AD");
      pax.put("name", p.getGivenName());
      pax.put("surname", p.getFamilyName());
    }
    body.put("clientReference", clientReference(request.getCtx().getIdempotencyKey()));
    body.put("remark", "TravelOS " + request.getCtx().getCorrelationId());
    JsonNode response = client.post("/hotel-api/1.0/bookings", body);
    return toCreateResponse(response.path("booking"));
  }

  /** Hotelbeds keeps at most 20 characters of clientReference: a stable digest of our key. */
  public static String clientReference(String idempotencyKey) {
    try {
      MessageDigest sha = MessageDigest.getInstance("SHA-256");
      return HexFormat.of()
          .formatHex(sha.digest(idempotencyKey.getBytes(StandardCharsets.UTF_8)))
          .substring(0, 20)
          .toUpperCase();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static CreateOrderResponse toCreateResponse(JsonNode booking) {
    String status = booking.path("status").asString("");
    return CreateOrderResponse.newBuilder()
        .setExternalOrderId(booking.path("reference").asString())
        .setRecordLocator(booking.path("reference").asString())
        .setStatus(
            "CANCELLED".equals(status)
                ? SupplierOrderStatus.CANCELLED
                : SupplierOrderStatus.CONFIRMED)
        .setCharged(
            Money.newBuilder()
                .setCurrency(booking.path("currency").asString("EUR"))
                .setAmountMinor(LiveHttp.minor(booking.path("totalNet").asString("0"))))
        .build();
  }

  @Override
  public ChangeOrderResponse changeOrder(ChangeOrderRequest request) {
    throw new SupplierException(
        "CHANGE_NOT_SUPPORTED", "hotelbeds bookings are changed by cancel + create", false);
  }

  @Override
  public CancelOrderResponse cancelOrder(CancelOrderRequest request) {
    JsonNode before =
        client.get("/hotel-api/1.0/bookings/" + request.getExternalOrderId()).path("booking");
    long paid = LiveHttp.minor(before.path("totalNet").asString("0"));
    String currency = before.path("currency").asString("EUR");
    JsonNode after =
        client
            .delete(
                "/hotel-api/1.0/bookings/"
                    + request.getExternalOrderId()
                    + "?cancellationFlag=CANCELLATION")
            .path("booking");
    long charged = LiveHttp.minor(after.path("totalNet").asString("0"));
    return CancelOrderResponse.newBuilder()
        .setExternalOrderId(request.getExternalOrderId())
        .setStatus(SupplierOrderStatus.CANCELLED)
        .setRefund(
            Money.newBuilder().setCurrency(currency).setAmountMinor(Math.max(0, paid - charged)))
        .build();
  }

  @Override
  public BookingStatus bookingStatus(GetBookingStatusRequest request) {
    JsonNode booking;
    if (!request.getExternalOrderId().isBlank()) {
      try {
        booking =
            client.get("/hotel-api/1.0/bookings/" + request.getExternalOrderId()).path("booking");
      } catch (SupplierException e) {
        if (e.code().startsWith("HTTP_404")) {
          return BookingStatus.newBuilder().setStatus(SupplierOrderStatus.NOT_FOUND).build();
        }
        throw e;
      }
    } else {
      LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneId.of("UTC"));
      JsonNode list =
          client.get(
              "/hotel-api/1.0/bookings?clientReference="
                  + clientReference(request.getIdempotencyKey())
                  + "&filterType=CREATION&start="
                  + today.minusDays(3)
                  + "&end="
                  + today.plusDays(1));
      JsonNode bookings = list.path("bookings").path("bookings");
      if (!bookings.isArray() || bookings.isEmpty()) {
        return BookingStatus.newBuilder().setStatus(SupplierOrderStatus.NOT_FOUND).build();
      }
      booking = bookings.get(0);
    }
    if (booking.isMissingNode() || booking.isNull()) {
      return BookingStatus.newBuilder().setStatus(SupplierOrderStatus.NOT_FOUND).build();
    }
    CreateOrderResponse c = toCreateResponse(booking);
    return BookingStatus.newBuilder()
        .setStatus(c.getStatus())
        .setExternalOrderId(c.getExternalOrderId())
        .setRecordLocator(c.getRecordLocator())
        .setCharged(c.getCharged())
        .build();
  }

  // ------------------------------------------------------------------ helpers

  /** The dates are the first two fields of a Hotelbeds rate key ("20261006|20261008|..."). */
  record RateKey(LocalDate checkIn, LocalDate checkOut) {
    static RateKey parse(String rateKey) {
      String[] parts = rateKey.split("\\|");
      java.time.format.DateTimeFormatter f = java.time.format.DateTimeFormatter.BASIC_ISO_DATE;
      try {
        return new RateKey(LocalDate.parse(parts[0], f), LocalDate.parse(parts[1], f));
      } catch (RuntimeException e) {
        throw new SupplierException("OFFER_UNKNOWN", "not a hotelbeds rate key", false);
      }
    }
  }

  static @Nullable Instant parseInstant(String text) {
    if (text == null || text.isBlank()) {
      return null;
    }
    try {
      return OffsetDateTime.parse(text).toInstant();
    } catch (DateTimeParseException e) {
      return null;
    }
  }

  private static Timestamp ts(Instant i) {
    return Timestamp.newBuilder().setSeconds(i.getEpochSecond()).setNanos(i.getNano()).build();
  }

  static Optional<Locations.Place> placeOf(String city) {
    return Locations.place(city);
  }
}
