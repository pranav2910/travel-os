package io.travelos.supplier.live.duffel;

import com.google.protobuf.Timestamp;
import io.travelos.common.geo.Locations;
import io.travelos.common.ids.IdPrefix;
import io.travelos.common.ids.Ids;
import io.travelos.contracts.common.v1.Cabin;
import io.travelos.contracts.common.v1.Money;
import io.travelos.contracts.offer.v1.AirOffer;
import io.travelos.contracts.offer.v1.CancellationTerms;
import io.travelos.contracts.offer.v1.FlightSegment;
import io.travelos.contracts.offer.v1.Journey;
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
import io.travelos.contracts.supplier.v1.PassengerDocument;
import io.travelos.contracts.supplier.v1.PriceOfferRequest;
import io.travelos.contracts.supplier.v1.PriceOfferResponse;
import io.travelos.contracts.supplier.v1.SearchAirRequest;
import io.travelos.contracts.supplier.v1.SearchAirResponse;
import io.travelos.contracts.supplier.v1.SupplierCapabilities;
import io.travelos.contracts.supplier.v1.SupplierOrderStatus;
import io.travelos.supplier.AirSupplier;
import io.travelos.supplier.live.LiveHttp;
import io.travelos.supplier.live.LiveIntegrationProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Duffel as an air supplier (Phase 4, ADR-0016). LIVE integration: everything here talks to
 * Duffel's API with the configured token; a {@code duffel_test_} token reaches Duffel's test
 * airlines and books nothing real, anything else spends money.
 *
 * <p>Duffel does not honour an idempotency key on order creation and cannot find an order by our
 * key, so the gateway's mutation ledger is what stands between a lost answer and a double booking:
 * an answer lost after the request left is reported as OUTCOME_UNKNOWN and a person reconciles
 * against Duffel's dashboard or by the order id in the ledger when it is known.
 */
public class DuffelAirSupplier implements AirSupplier {
  private static final Logger log = LoggerFactory.getLogger(DuffelAirSupplier.class);
  static final String PROVIDER = DuffelClient.PROVIDER;

  private final DuffelClient client;
  private final LiveIntegrationProperties.Duffel props;
  private final Clock clock;

  public DuffelAirSupplier(
      DuffelClient client, LiveIntegrationProperties.Duffel props, Clock clock) {
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
        .addTypes(OfferType.AIR)
        .setChangeSupported(false)
        .setCancelSupported(true)
        .setStatusLookupSupported(true)
        .setNotificationsSupported(false)
        .setIntegration("LIVE")
        .setMutationsIdempotent(false)
        .setReconciliationByKeySupported(false)
        .setNegotiatedRatesSupported(!props.privateFares().isEmpty())
        .build();
  }

  // ------------------------------------------------------------------ search

  @Override
  public SearchAirResponse search(SearchAirRequest request) {
    if (request.getOrigin().isBlank() || request.getDestination().isBlank()) {
      throw new SupplierException("INVALID_ROUTE", "origin and destination are required", false);
    }
    if (!request.hasOutboundDeparture() || !request.getOutboundDeparture().hasNotBefore()) {
      throw new SupplierException(
          "INVALID_WINDOW", "outbound_departure.not_before is required", false);
    }
    ObjectNode data = LiveHttp.JSON.createObjectNode();
    ArrayNode slices = data.putArray("slices");
    slices.add(
        slice(
            request.getOrigin(),
            request.getDestination(),
            localDate(request.getOutboundDeparture().getNotBefore(), request.getOrigin())));
    if (request.hasReturnDeparture() && request.getReturnDeparture().hasNotBefore()) {
      slices.add(
          slice(
              request.getDestination(),
              request.getOrigin(),
              localDate(request.getReturnDeparture().getNotBefore(), request.getDestination())));
    }
    ArrayNode passengers = data.putArray("passengers");
    for (int i = 0; i < Math.max(1, request.getPassengers()); i++) {
      passengers.addObject().put("type", "adult");
    }
    data.put("cabin_class", cabinClass(request.getCabinsList()));
    if (!props.privateFares().isEmpty()) {
      ObjectNode fares = data.putObject("private_fares");
      for (Map.Entry<String, String> e : props.privateFares().entrySet()) {
        fares.putArray(e.getKey()).addObject().put("corporate_code", e.getValue());
      }
    }
    ObjectNode body = LiveHttp.JSON.createObjectNode();
    body.set("data", data);
    JsonNode offerRequest = client.post("/air/offer_requests?return_offers=true", body);
    String session = Ids.newId(IdPrefix.SEARCH_SESSION);
    SearchAirResponse.Builder response = SearchAirResponse.newBuilder().setSearchSessionId(session);
    Instant now = clock.instant();
    for (JsonNode offer : offerRequest.path("offers")) {
      try {
        Offer mapped = offer(offer, session, now);
        if (mapped != null) {
          response.addOffers(mapped);
        }
      } catch (RuntimeException e) {
        log.warn("duffel offer {} skipped: {}", offer.path("id").asString(), e.getMessage());
      }
    }
    return response.build();
  }

  private static ObjectNode slice(String origin, String destination, LocalDate date) {
    ObjectNode s = LiveHttp.JSON.createObjectNode();
    s.put("origin", origin);
    s.put("destination", destination);
    s.put("departure_date", date.toString());
    return s;
  }

  /** Duffel takes one cabin per request: the lowest one asked for, so nothing below is hidden. */
  static String cabinClass(List<Cabin> cabins) {
    if (cabins.isEmpty() || cabins.contains(Cabin.ECONOMY)) {
      return "economy";
    }
    if (cabins.contains(Cabin.PREMIUM_ECONOMY)) {
      return "premium_economy";
    }
    if (cabins.contains(Cabin.BUSINESS)) {
      return "business";
    }
    return "first";
  }

  // ------------------------------------------------------------------ price / quote

  @Override
  public PriceOfferResponse price(PriceOfferRequest request) {
    JsonNode offer = client.get("/air/offers/" + request.getProviderOfferId());
    if (offer.isMissingNode() || offer.isNull()) {
      throw new SupplierException(
          "OFFER_UNKNOWN", "duffel knows no offer " + request.getProviderOfferId(), false);
    }
    Offer mapped = offer(offer, "", clock.instant());
    if (mapped == null) {
      throw new SupplierException(
          "OFFER_EXPIRED", "offer " + request.getProviderOfferId() + " is no longer sold", false);
    }
    // Duffel re-prices on read; the caller compares the amount with what it searched.
    return PriceOfferResponse.newBuilder().setOffer(mapped).setPriceChanged(false).build();
  }

  // ------------------------------------------------------------------ order

  @Override
  public CreateOrderResponse createOrder(CreateOrderRequest request) {
    JsonNode offer = client.get("/air/offers/" + request.getProviderOfferId());
    if (offer.isMissingNode() || offer.isNull()) {
      throw new SupplierException(
          "OFFER_UNKNOWN", "duffel knows no offer " + request.getProviderOfferId(), false);
    }
    List<String> passengerIds = new ArrayList<>();
    for (JsonNode p : offer.path("passengers")) {
      passengerIds.add(p.path("id").asString());
    }
    if (passengerIds.size() != request.getPassengersCount()) {
      throw new SupplierException(
          "PASSENGER_COUNT_MISMATCH",
          "offer was searched for "
              + passengerIds.size()
              + " passenger(s), order names "
              + request.getPassengersCount(),
          false);
    }
    boolean documentsRequired =
        offer.path("passenger_identity_documents_required").asBoolean(false);
    ObjectNode data = LiveHttp.JSON.createObjectNode();
    data.put("type", "instant");
    data.putArray("selected_offers").add(request.getProviderOfferId());
    ArrayNode passengers = data.putArray("passengers");
    for (int i = 0; i < request.getPassengersCount(); i++) {
      Passenger p = request.getPassengers(i);
      ObjectNode node = passengers.addObject();
      node.put("id", passengerIds.get(i));
      node.put("given_name", p.getGivenName());
      node.put("family_name", p.getFamilyName());
      node.put("email", p.getEmail());
      if (p.getDateOfBirth().isBlank() || p.getGender().isBlank() || p.getPhone().isBlank()) {
        throw new SupplierException(
            "PASSENGER_DETAILS_INCOMPLETE",
            "duffel needs date of birth, gender and phone for "
                + p.getGivenName()
                + " "
                + p.getFamilyName()
                + "; complete the traveler profile",
            false);
      }
      node.put("born_on", p.getDateOfBirth());
      node.put("gender", gender(p.getGender()));
      node.put(
          "title",
          p.getTitle().isBlank()
              ? ("F".equalsIgnoreCase(p.getGender()) ? "ms" : "mr")
              : p.getTitle().toLowerCase(Locale.ROOT));
      node.put("phone_number", p.getPhone());
      if (documentsRequired) {
        if (p.getDocumentsCount() == 0) {
          throw new SupplierException(
              "PASSENGER_DOCUMENT_REQUIRED",
              "this fare needs a travel document for " + p.getGivenName() + " " + p.getFamilyName(),
              false);
        }
        ArrayNode docs = node.putArray("identity_documents");
        for (PassengerDocument d : p.getDocumentsList()) {
          ObjectNode doc = docs.addObject();
          doc.put(
              "type",
              "PASSPORT".equals(d.getType()) ? "passport" : d.getType().toLowerCase(Locale.ROOT));
          doc.put("unique_identifier", d.getNumber());
          doc.put("issuing_country_code", d.getIssuingCountry());
          doc.put("expires_on", d.getExpiresOn());
        }
      }
      if (!p.getLoyaltyNumber().isBlank() && !p.getLoyaltyProgram().isBlank()) {
        ObjectNode loyalty = node.putArray("loyalty_programme_accounts").addObject();
        loyalty.put("airline_iata_code", p.getLoyaltyProgram());
        loyalty.put("account_number", p.getLoyaltyNumber());
      }
    }
    ObjectNode payment = data.putArray("payments").addObject();
    payment.put("type", "balance");
    payment.put("currency", offer.path("total_currency").asString());
    payment.put("amount", offer.path("total_amount").asString());
    ObjectNode metadata = data.putObject("metadata");
    metadata.put("travelos_idempotency_key", request.getCtx().getIdempotencyKey());
    metadata.put("travelos_correlation_id", request.getCtx().getCorrelationId());
    ObjectNode body = LiveHttp.JSON.createObjectNode();
    body.set("data", data);
    JsonNode order = client.post("/air/orders", body);
    return toCreateResponse(order);
  }

  private static String gender(String g) {
    return switch (g.toUpperCase(Locale.ROOT)) {
      case "F" -> "f";
      case "M" -> "m";
      default -> "m"; // Duffel accepts m|f only; documented limitation for X
    };
  }

  private static CreateOrderResponse toCreateResponse(JsonNode order) {
    CreateOrderResponse.Builder b =
        CreateOrderResponse.newBuilder()
            .setExternalOrderId(order.path("id").asString())
            .setRecordLocator(order.path("booking_reference").asString())
            .setStatus(
                order.hasNonNull("cancelled_at")
                    ? SupplierOrderStatus.CANCELLED
                    : SupplierOrderStatus.CONFIRMED)
            .setCharged(
                money(
                    order.path("total_currency").asString(),
                    order.path("total_amount").asString()));
    for (JsonNode doc : order.path("documents")) {
      if (doc.hasNonNull("unique_identifier")) {
        b.addTicketNumbers(doc.get("unique_identifier").asString());
      }
    }
    return b.build();
  }

  @Override
  public ChangeOrderResponse changeOrder(ChangeOrderRequest request) {
    throw new SupplierException(
        "CHANGE_NOT_SUPPORTED",
        "duffel order changes are not wired; a change is cancel + create",
        false);
  }

  @Override
  public CancelOrderResponse cancelOrder(CancelOrderRequest request) {
    ObjectNode body = LiveHttp.JSON.createObjectNode();
    body.putObject("data").put("order_id", request.getExternalOrderId());
    JsonNode pending = client.post("/air/order_cancellations", body);
    String cancellationId = pending.path("id").asString();
    JsonNode confirmed =
        client.post(
            "/air/order_cancellations/" + cancellationId + "/actions/confirm",
            LiveHttp.JSON.createObjectNode());
    JsonNode source = confirmed.isMissingNode() ? pending : confirmed;
    CancelOrderResponse.Builder b =
        CancelOrderResponse.newBuilder()
            .setExternalOrderId(request.getExternalOrderId())
            .setStatus(SupplierOrderStatus.CANCELLED);
    if (source.hasNonNull("refund_amount")) {
      b.setRefund(
          money(
              source.path("refund_currency").asString(), source.path("refund_amount").asString()));
    }
    return b.build();
  }

  @Override
  public BookingStatus bookingStatus(GetBookingStatusRequest request) {
    if (request.getExternalOrderId().isBlank()) {
      // Duffel cannot find an order by our key: honest NOT_FOUND, the ledger keeps the attempt.
      return BookingStatus.newBuilder().setStatus(SupplierOrderStatus.NOT_FOUND).build();
    }
    JsonNode order;
    try {
      order = client.get("/air/orders/" + request.getExternalOrderId());
    } catch (SupplierException e) {
      if (e.code().startsWith("HTTP_404") || "NOT_FOUND".equals(e.code())) {
        return BookingStatus.newBuilder().setStatus(SupplierOrderStatus.NOT_FOUND).build();
      }
      throw e;
    }
    if (order.isMissingNode() || order.isNull()) {
      return BookingStatus.newBuilder().setStatus(SupplierOrderStatus.NOT_FOUND).build();
    }
    CreateOrderResponse c = toCreateResponse(order);
    return BookingStatus.newBuilder()
        .setStatus(c.getStatus())
        .setExternalOrderId(c.getExternalOrderId())
        .setRecordLocator(c.getRecordLocator())
        .setCharged(c.getCharged())
        .addAllTicketNumbers(c.getTicketNumbersList())
        .build();
  }

  // ------------------------------------------------------------------ mapping

  /** Duffel offer → our Offer, or null when it is already expired. */
  @Nullable Offer offer(JsonNode o, String session, Instant now) {
    Instant expires = instant(o.path("expires_at").asString(), ZoneOffset.UTC);
    if (expires != null && !expires.isAfter(now)) {
      return null;
    }
    AirOffer.Builder air = AirOffer.newBuilder();
    JsonNode slices = o.path("slices");
    if (slices.size() > 0) {
      air.setOutbound(journey(slices.get(0)));
    }
    if (slices.size() > 1) {
      air.setInbound(journey(slices.get(1)));
    }
    JsonNode conditions = o.path("conditions");
    JsonNode refund = conditions.path("refund_before_departure");
    JsonNode change = conditions.path("change_before_departure");
    boolean refundable = refund.path("allowed").asBoolean(false);
    Money total = money(o.path("total_currency").asString(), o.path("total_amount").asString());
    Offer.Builder b =
        Offer.newBuilder()
            .setOfferId(Ids.newId(IdPrefix.OFFER))
            .setSearchSessionId(session)
            .setProvider(PROVIDER)
            .setProviderOfferId(o.path("id").asString())
            .setType(OfferType.AIR)
            .setTotal(total)
            .setRefundable(refundable)
            .setAir(air)
            .setNegotiated(o.path("private_fares").isArray() && !o.path("private_fares").isEmpty());
    if (expires != null) {
      b.setExpiresAt(ts(expires));
    }
    if (change.path("allowed").asBoolean(false) && change.hasNonNull("penalty_amount")) {
      b.setChangePenalty(
          money(
              change.path("penalty_currency").asString(total.getCurrency()),
              change.path("penalty_amount").asString()));
    }
    CancellationTerms.Builder terms = CancellationTerms.newBuilder().setRefundable(refundable);
    if (refund.hasNonNull("penalty_amount")) {
      terms.setPenalty(
          money(
              refund.path("penalty_currency").asString(total.getCurrency()),
              refund.path("penalty_amount").asString()));
    } else if (!refundable) {
      terms.setPenalty(total);
    }
    b.setCancellation(terms);
    if (o.path("private_fares").isArray() && !o.path("private_fares").isEmpty()) {
      b.setRateCode(o.path("private_fares").get(0).path("corporate_code").asString());
    }
    return b.build();
  }

  private static Journey journey(JsonNode slice) {
    Journey.Builder j = Journey.newBuilder();
    for (JsonNode s : slice.path("segments")) {
      String origin = s.path("origin").path("iata_code").asString();
      String destination = s.path("destination").path("iata_code").asString();
      FlightSegment.Builder seg =
          FlightSegment.newBuilder()
              .setSegmentId(s.path("id").asString())
              .setCarrier(s.path("marketing_carrier").path("iata_code").asString())
              .setFlightNumber(
                  s.path("marketing_carrier").path("iata_code").asString()
                      + s.path("marketing_carrier_flight_number").asString())
              .setOrigin(origin)
              .setDestination(destination)
              .setAircraft(s.path("aircraft").path("name").asString(""));
      Instant dep = instant(s.path("departing_at").asString(), zone(origin));
      Instant arr = instant(s.path("arriving_at").asString(), zone(destination));
      if (dep != null) {
        seg.setDeparture(ts(dep));
      }
      if (arr != null) {
        seg.setArrival(ts(arr));
      }
      JsonNode pax = s.path("passengers");
      if (pax.size() > 0) {
        seg.setCabin(cabin(pax.get(0).path("cabin_class").asString("economy")));
        seg.setBookingClass(pax.get(0).path("fare_basis_code").asString(""));
      }
      j.addSegments(seg);
    }
    return j.build();
  }

  static Cabin cabin(String duffel) {
    return switch (duffel) {
      case "premium_economy" -> Cabin.PREMIUM_ECONOMY;
      case "business" -> Cabin.BUSINESS;
      case "first" -> Cabin.FIRST;
      default -> Cabin.ECONOMY;
    };
  }

  private static ZoneId zone(String iata) {
    return Locations.zoneOf(iata).orElse(ZoneOffset.UTC);
  }

  /** Duffel gives local wall-clock times without an offset for segments, ISO instants elsewhere. */
  static @Nullable Instant instant(String text, ZoneId localZone) {
    if (text == null || text.isBlank()) {
      return null;
    }
    try {
      return OffsetDateTime.parse(text).toInstant();
    } catch (DateTimeParseException ignored) {
      // local time
    }
    try {
      return LocalDateTime.parse(text).atZone(localZone).toInstant();
    } catch (DateTimeParseException e) {
      return null;
    }
  }

  private static LocalDate localDate(Timestamp at, String iata) {
    return Instant.ofEpochSecond(at.getSeconds()).atZone(zone(iata)).toLocalDate();
  }

  static Money money(String currency, String amount) {
    return Money.newBuilder().setCurrency(currency).setAmountMinor(LiveHttp.minor(amount)).build();
  }

  private static Timestamp ts(Instant i) {
    return Timestamp.newBuilder().setSeconds(i.getEpochSecond()).setNanos(i.getNano()).build();
  }
}
