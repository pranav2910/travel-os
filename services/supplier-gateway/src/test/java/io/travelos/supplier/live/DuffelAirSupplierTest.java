package io.travelos.supplier.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.google.protobuf.Timestamp;
import io.travelos.contracts.common.v1.Cabin;
import io.travelos.contracts.common.v1.RequestContext;
import io.travelos.contracts.common.v1.TimeWindow;
import io.travelos.contracts.offer.v1.Offer;
import io.travelos.contracts.supplier.v1.BookingStatus;
import io.travelos.contracts.supplier.v1.CancelOrderRequest;
import io.travelos.contracts.supplier.v1.CancelOrderResponse;
import io.travelos.contracts.supplier.v1.CreateOrderRequest;
import io.travelos.contracts.supplier.v1.CreateOrderResponse;
import io.travelos.contracts.supplier.v1.GetBookingStatusRequest;
import io.travelos.contracts.supplier.v1.Passenger;
import io.travelos.contracts.supplier.v1.SearchAirRequest;
import io.travelos.contracts.supplier.v1.SearchAirResponse;
import io.travelos.contracts.supplier.v1.SupplierOrderStatus;
import io.travelos.supplier.AirSupplier.SupplierException;
import io.travelos.supplier.live.duffel.DuffelAirSupplier;
import io.travelos.supplier.live.duffel.DuffelClient;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Contract test against Duffel's documented v2 shapes, served by a scripted HTTP server. This
 * proves the mapping and the request bodies; it is NOT a live verification (see
 * DuffelLiveSearchTest, credential-gated).
 */
class DuffelAirSupplierTest {

  private static final Instant NOW = Instant.parse("2026-09-24T12:00:00Z");
  private MockRestServiceServer server;
  private DuffelAirSupplier duffel;

  @BeforeEach
  void setUp() {
    RestClient.Builder builder = RestClient.builder();
    server = MockRestServiceServer.bindTo(builder).build();
    LiveIntegrationProperties.Duffel props =
        new LiveIntegrationProperties.Duffel(
            "duffel_test_abc", "https://api.duffel.test", null, Map.of("UA", "ACME2026"));
    duffel =
        new DuffelAirSupplier(
            new DuffelClient(builder, props), props, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void capabilitiesSayLiveNotIdempotentAndNegotiated() {
    var caps = duffel.capabilities();
    assertThat(caps.getIntegration()).isEqualTo("LIVE");
    assertThat(caps.getMutationsIdempotent()).isFalse();
    assertThat(caps.getReconciliationByKeySupported()).isFalse();
    assertThat(caps.getNegotiatedRatesSupported()).isTrue();
    assertThat(caps.getChangeSupported()).isFalse();
  }

  @Test
  void searchPostsAnOfferRequestAndMapsOffersWithLocalTimesConditionsAndPrivateFares() {
    server
        .expect(requestTo("https://api.duffel.test/air/offer_requests?return_offers=true"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("Authorization", "Bearer duffel_test_abc"))
        .andExpect(header("Duffel-Version", "v2"))
        .andExpect(jsonPath("$.data.slices[0].origin").value("BOS"))
        .andExpect(jsonPath("$.data.slices[0].destination").value("SEA"))
        .andExpect(jsonPath("$.data.slices[0].departure_date").value("2026-10-06"))
        .andExpect(jsonPath("$.data.slices[1].origin").value("SEA"))
        .andExpect(jsonPath("$.data.passengers[0].type").value("adult"))
        .andExpect(jsonPath("$.data.cabin_class").value("economy"))
        .andExpect(jsonPath("$.data.private_fares.UA[0].corporate_code").value("ACME2026"))
        .andRespond(withSuccess(OFFER_REQUEST, MediaType.APPLICATION_JSON));

    SearchAirResponse response =
        duffel.search(
            SearchAirRequest.newBuilder()
                .setCtx(ctx())
                .setOrigin("BOS")
                .setDestination("SEA")
                .setPassengers(1)
                .addCabins(Cabin.ECONOMY)
                .addCabins(Cabin.BUSINESS)
                .setOutboundDeparture(
                    TimeWindow.newBuilder()
                        .setNotBefore(ts("2026-10-06T10:00:00Z"))
                        .setNotAfter(ts("2026-10-06T23:00:00Z")))
                .setReturnDeparture(
                    TimeWindow.newBuilder()
                        .setNotBefore(ts("2026-10-08T00:00:00Z"))
                        .setNotAfter(ts("2026-10-08T23:00:00Z")))
                .build());
    server.verify();
    assertThat(response.getOffersList()).as("the expired offer is dropped").hasSize(1);
    Offer o = response.getOffers(0);
    assertThat(o.getProvider()).isEqualTo("duffel");
    assertThat(o.getProviderOfferId()).isEqualTo("off_0000AeY0lsGYu4k7aWs3Yq");
    assertThat(o.getOfferId()).startsWith("off_");
    assertThat(o.getTotal().getCurrency()).isEqualTo("USD");
    assertThat(o.getTotal().getAmountMinor()).isEqualTo(47550);
    assertThat(o.getNegotiated()).isTrue();
    assertThat(o.getRateCode()).isEqualTo("ACME2026");
    assertThat(o.getRefundable()).isFalse();
    assertThat(o.getChangePenalty().getAmountMinor()).isEqualTo(7500);
    assertThat(o.getCancellation().getPenalty().getAmountMinor())
        .as("non-refundable: the whole price")
        .isEqualTo(47550);
    assertThat(o.getExpiresAt().getSeconds())
        .isEqualTo(Instant.parse("2026-09-24T12:30:00Z").getEpochSecond());
    var seg = o.getAir().getOutbound().getSegments(0);
    assertThat(seg.getCarrier()).isEqualTo("UA");
    assertThat(seg.getFlightNumber()).isEqualTo("UA123");
    assertThat(seg.getOrigin()).isEqualTo("BOS");
    assertThat(seg.getCabin()).isEqualTo(Cabin.ECONOMY);
    // 08:05 Boston local on 2026-10-06 is 12:05Z (EDT)
    assertThat(Instant.ofEpochSecond(seg.getDeparture().getSeconds()))
        .isEqualTo(Instant.parse("2026-10-06T12:05:00Z"));
    // 11:30 Seattle local is 18:30Z (PDT)
    assertThat(Instant.ofEpochSecond(seg.getArrival().getSeconds()))
        .isEqualTo(Instant.parse("2026-10-06T18:30:00Z"));
    assertThat(o.getAir().getInbound().getSegmentsCount()).isEqualTo(1);
  }

  @Test
  void createOrderReusesTheOffersPassengerIdsSendsTheProfileDetailsAndMapsTheOrder() {
    server
        .expect(requestTo("https://api.duffel.test/air/offers/off_1"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess(OFFER_FOR_ORDER, MediaType.APPLICATION_JSON));
    server
        .expect(requestTo("https://api.duffel.test/air/orders"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(jsonPath("$.data.type").value("instant"))
        .andExpect(jsonPath("$.data.selected_offers[0]").value("off_1"))
        .andExpect(jsonPath("$.data.passengers[0].id").value("pas_1"))
        .andExpect(jsonPath("$.data.passengers[0].born_on").value("1990-04-12"))
        .andExpect(jsonPath("$.data.passengers[0].gender").value("f"))
        .andExpect(jsonPath("$.data.passengers[0].title").value("ms"))
        .andExpect(jsonPath("$.data.passengers[0].phone_number").value("+16175550101"))
        .andExpect(
            jsonPath("$.data.passengers[0].identity_documents[0].unique_identifier")
                .value("X123456789"))
        .andExpect(jsonPath("$.data.payments[0].type").value("balance"))
        .andExpect(jsonPath("$.data.payments[0].amount").value("475.50"))
        .andExpect(jsonPath("$.data.metadata.travelos_idempotency_key").value("ord_1:itm_1"))
        .andRespond(withSuccess(ORDER, MediaType.APPLICATION_JSON));

    CreateOrderResponse created =
        duffel.createOrder(
            CreateOrderRequest.newBuilder()
                .setCtx(ctx().toBuilder().setIdempotencyKey("ord_1:itm_1"))
                .setProvider("duffel")
                .setProviderOfferId("off_1")
                .addPassengers(
                    Passenger.newBuilder()
                        .setGivenName("Alice")
                        .setFamilyName("Nguyen")
                        .setEmail("alice@acme.example")
                        .setPhone("+16175550101")
                        .setDateOfBirth("1990-04-12")
                        .setGender("F")
                        .addDocuments(
                            io.travelos.contracts.supplier.v1.PassengerDocument.newBuilder()
                                .setType("PASSPORT")
                                .setNumber("X123456789")
                                .setIssuingCountry("US")
                                .setExpiresOn("2031-01-15")))
                .setPaymentToken("tok_corp_visa_sandbox")
                .build());
    server.verify();
    assertThat(created.getExternalOrderId()).isEqualTo("ord_00009hthhsUZ8W4LxQgkjo");
    assertThat(created.getRecordLocator()).isEqualTo("RZPNX8");
    assertThat(created.getStatus()).isEqualTo(SupplierOrderStatus.CONFIRMED);
    assertThat(created.getCharged().getAmountMinor()).isEqualTo(47550);
    assertThat(created.getTicketNumbersList()).containsExactly("0162345678901");
  }

  @Test
  void aProfileWithoutDateOfBirthIsRefusedBeforeAnythingIsSent() {
    server
        .expect(requestTo("https://api.duffel.test/air/offers/off_1"))
        .andRespond(withSuccess(OFFER_FOR_ORDER, MediaType.APPLICATION_JSON));
    assertThatThrownBy(
            () ->
                duffel.createOrder(
                    CreateOrderRequest.newBuilder()
                        .setCtx(ctx())
                        .setProviderOfferId("off_1")
                        .addPassengers(
                            Passenger.newBuilder()
                                .setGivenName("Alice")
                                .setFamilyName("Nguyen")
                                .setEmail("a@b.c"))
                        .build()))
        .isInstanceOf(SupplierException.class)
        .extracting(e -> ((SupplierException) e).code())
        .isEqualTo("PASSENGER_DETAILS_INCOMPLETE");
    server.verify();
  }

  @Test
  void cancellationIsCreatedThenConfirmedAndTheRefundIsReported() {
    server
        .expect(requestTo("https://api.duffel.test/air/order_cancellations"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(jsonPath("$.data.order_id").value("ord_1"))
        .andRespond(
            withSuccess(
                "{\"data\":{\"id\":\"ore_1\",\"order_id\":\"ord_1\",\"refund_amount\":\"400.00\",\"refund_currency\":\"USD\"}}",
                MediaType.APPLICATION_JSON));
    server
        .expect(requestTo("https://api.duffel.test/air/order_cancellations/ore_1/actions/confirm"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(
            withSuccess(
                "{\"data\":{\"id\":\"ore_1\",\"refund_amount\":\"400.00\",\"refund_currency\":\"USD\",\"confirmed_at\":\"2026-09-24T12:01:00Z\"}}",
                MediaType.APPLICATION_JSON));
    CancelOrderResponse cancelled =
        duffel.cancelOrder(
            CancelOrderRequest.newBuilder().setCtx(ctx()).setExternalOrderId("ord_1").build());
    server.verify();
    assertThat(cancelled.getStatus()).isEqualTo(SupplierOrderStatus.CANCELLED);
    assertThat(cancelled.getRefund().getAmountMinor()).isEqualTo(40000);
  }

  @Test
  void statusByOurKeyIsHonestlyNotFoundAndByIdIsLookedUp() {
    BookingStatus byKey =
        duffel.bookingStatus(
            GetBookingStatusRequest.newBuilder().setCtx(ctx()).setIdempotencyKey("k").build());
    assertThat(byKey.getStatus()).isEqualTo(SupplierOrderStatus.NOT_FOUND);
    server
        .expect(requestTo("https://api.duffel.test/air/orders/ord_00009hthhsUZ8W4LxQgkjo"))
        .andRespond(withSuccess(ORDER, MediaType.APPLICATION_JSON));
    BookingStatus byId =
        duffel.bookingStatus(
            GetBookingStatusRequest.newBuilder()
                .setCtx(ctx())
                .setExternalOrderId("ord_00009hthhsUZ8W4LxQgkjo")
                .build());
    assertThat(byId.getStatus()).isEqualTo(SupplierOrderStatus.CONFIRMED);
    assertThat(byId.getRecordLocator()).isEqualTo("RZPNX8");
  }

  @Test
  void duffelErrorsBecomeSupplierFailuresWithTheirCodeAndRetryability() {
    server
        .expect(requestTo("https://api.duffel.test/air/offers/off_gone"))
        .andRespond(
            withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                .contentType(MediaType.APPLICATION_JSON)
                .body(
                    "{\"errors\":[{\"code\":\"offer_no_longer_available\",\"message\":\"The offer has expired\",\"title\":\"Offer no longer available\"}]}"));
    assertThatThrownBy(
            () ->
                duffel.price(
                    io.travelos.contracts.supplier.v1.PriceOfferRequest.newBuilder()
                        .setCtx(ctx())
                        .setProviderOfferId("off_gone")
                        .build()))
        .isInstanceOf(SupplierException.class)
        .satisfies(
            e -> {
              assertThat(((SupplierException) e).code()).isEqualTo("OFFER_NO_LONGER_AVAILABLE");
              assertThat(((SupplierException) e).retryable()).isFalse();
            });
    server.reset();
    server
        .expect(requestTo("https://api.duffel.test/air/offers/off_x"))
        .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).body(""));
    assertThatThrownBy(
            () ->
                duffel.price(
                    io.travelos.contracts.supplier.v1.PriceOfferRequest.newBuilder()
                        .setCtx(ctx())
                        .setProviderOfferId("off_x")
                        .build()))
        .isInstanceOf(SupplierException.class)
        .satisfies(
            e ->
                assertThat(((SupplierException) e).retryable())
                    .as("the answer may have been lost")
                    .isTrue());
  }

  // ------------------------------------------------------------------ fixtures (Duffel v2 shapes)

  private static RequestContext ctx() {
    return RequestContext.newBuilder()
        .setTenantId("acme")
        .setCorrelationId("trip_01ARZ3NDEKTSV4RRFFQ69G5FAV")
        .setPrincipal(
            io.travelos.contracts.common.v1.Principal.newBuilder()
                .setKind(io.travelos.contracts.common.v1.Principal.Kind.SERVICE)
                .setId("service/order"))
        .build();
  }

  private static Timestamp ts(String iso) {
    return Timestamp.newBuilder().setSeconds(Instant.parse(iso).getEpochSecond()).build();
  }

  private static final String OFFER_REQUEST =
      """
      {"data":{"id":"orq_1","offers":[
        {"id":"off_0000AeY0lsGYu4k7aWs3Yq","total_amount":"475.50","total_currency":"USD",
         "expires_at":"2026-09-24T12:30:00Z","owner":{"iata_code":"UA","name":"United"},
         "private_fares":[{"corporate_code":"ACME2026","type":"corporate"}],
         "passenger_identity_documents_required":false,
         "conditions":{"refund_before_departure":{"allowed":false,"penalty_amount":null,"penalty_currency":null},
                       "change_before_departure":{"allowed":true,"penalty_amount":"75.00","penalty_currency":"USD"}},
         "passengers":[{"id":"pas_1","type":"adult"}],
         "slices":[
           {"origin":{"iata_code":"BOS"},"destination":{"iata_code":"SEA"},
            "segments":[{"id":"seg_1","marketing_carrier":{"iata_code":"UA"},"marketing_carrier_flight_number":"123",
                         "origin":{"iata_code":"BOS"},"destination":{"iata_code":"SEA"},
                         "departing_at":"2026-10-06T08:05:00","arriving_at":"2026-10-06T11:30:00",
                         "aircraft":{"name":"Boeing 737-900"},
                         "passengers":[{"passenger_id":"pas_1","cabin_class":"economy","fare_basis_code":"KAA0AFEN"}]}]},
           {"origin":{"iata_code":"SEA"},"destination":{"iata_code":"BOS"},
            "segments":[{"id":"seg_2","marketing_carrier":{"iata_code":"UA"},"marketing_carrier_flight_number":"456",
                         "origin":{"iata_code":"SEA"},"destination":{"iata_code":"BOS"},
                         "departing_at":"2026-10-08T07:00:00","arriving_at":"2026-10-08T15:20:00",
                         "passengers":[{"passenger_id":"pas_1","cabin_class":"economy"}]}]}]},
        {"id":"off_expired","total_amount":"300.00","total_currency":"USD","expires_at":"2026-09-24T11:00:00Z",
         "conditions":{},"passengers":[{"id":"pas_2"}],"slices":[]}
      ]}}
      """;

  private static final String OFFER_FOR_ORDER =
      """
      {"data":{"id":"off_1","total_amount":"475.50","total_currency":"USD","expires_at":"2026-09-24T12:30:00Z",
        "passenger_identity_documents_required":true,
        "conditions":{"refund_before_departure":{"allowed":true,"penalty_amount":"50.00","penalty_currency":"USD"}},
        "passengers":[{"id":"pas_1","type":"adult"}],
        "slices":[{"origin":{"iata_code":"BOS"},"destination":{"iata_code":"SEA"},"segments":[]}]}}
      """;

  private static final String ORDER =
      """
      {"data":{"id":"ord_00009hthhsUZ8W4LxQgkjo","booking_reference":"RZPNX8","total_amount":"475.50","total_currency":"USD",
        "cancelled_at":null,"documents":[{"type":"electronic_ticket","unique_identifier":"0162345678901"}],
        "passengers":[{"id":"pas_1","given_name":"Alice","family_name":"Nguyen"}]}}
      """;
}
