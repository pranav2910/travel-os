package io.travelos.supplier.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import io.travelos.contracts.common.v1.RequestContext;
import io.travelos.contracts.offer.v1.Offer;
import io.travelos.contracts.supplier.v1.BookingStatus;
import io.travelos.contracts.supplier.v1.CancelOrderRequest;
import io.travelos.contracts.supplier.v1.CancelOrderResponse;
import io.travelos.contracts.supplier.v1.CreateOrderRequest;
import io.travelos.contracts.supplier.v1.CreateOrderResponse;
import io.travelos.contracts.supplier.v1.GetBookingStatusRequest;
import io.travelos.contracts.supplier.v1.Passenger;
import io.travelos.contracts.supplier.v1.SearchHotelsRequest;
import io.travelos.contracts.supplier.v1.SearchHotelsResponse;
import io.travelos.contracts.supplier.v1.SupplierOrderStatus;
import io.travelos.supplier.AirSupplier.SupplierException;
import io.travelos.supplier.live.hotelbeds.HotelbedsClient;
import io.travelos.supplier.live.hotelbeds.HotelbedsHotelSupplier;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** Contract test against Hotelbeds APItude 1.0 shapes on a scripted server; not a live check. */
class HotelbedsHotelSupplierTest {

  private static final Instant NOW = Instant.parse("2026-09-24T12:00:00Z");
  private MockRestServiceServer server;
  private HotelbedsHotelSupplier hotelbeds;

  @BeforeEach
  void setUp() {
    RestClient.Builder builder = RestClient.builder();
    server = MockRestServiceServer.bindTo(builder).build();
    LiveIntegrationProperties.Hotelbeds props =
        new LiveIntegrationProperties.Hotelbeds(
            "key123", "secret456", "https://api.test.hotelbeds.example", null, 25);
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    hotelbeds =
        new HotelbedsHotelSupplier(new HotelbedsClient(builder, props, clock), props, clock);
  }

  @Test
  void searchSignsTheRequestSearchesByTheCitysCoordinatesAndMapsRates() {
    // SHA-256("key123" + "secret456" + 1790510400)
    server
        .expect(requestTo("https://api.test.hotelbeds.example/hotel-api/1.0/hotels"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("Api-key", "key123"))
        .andExpect(header("X-Signature", org.hamcrest.Matchers.matchesPattern("[0-9a-f]{64}")))
        .andExpect(jsonPath("$.stay.checkIn").value("2026-10-06"))
        .andExpect(jsonPath("$.stay.checkOut").value("2026-10-08"))
        .andExpect(jsonPath("$.occupancies[0].adults").value(1))
        .andExpect(jsonPath("$.geolocation.latitude").value(47.45))
        .andExpect(jsonPath("$.geolocation.longitude").value(-122.309))
        .andExpect(jsonPath("$.geolocation.radius").value(25))
        .andRespond(withSuccess(AVAILABILITY, MediaType.APPLICATION_JSON));
    SearchHotelsResponse response =
        hotelbeds.searchHotels(
            SearchHotelsRequest.newBuilder()
                .setCtx(ctx())
                .setCity("SEA")
                .setCheckInDate("2026-10-06")
                .setCheckOutDate("2026-10-08")
                .setGuests(1)
                .build());
    server.verify();
    assertThat(response.getOffersList()).as("RECHECK rates are left to the quote step").hasSize(1);
    Offer o = response.getOffers(0);
    assertThat(o.getProvider()).isEqualTo("hotelbeds");
    assertThat(o.getProviderOfferId()).startsWith("20261006|20261008|W|1|12345|DBL.ST");
    assertThat(o.getTotal().getCurrency()).isEqualTo("USD");
    assertThat(o.getTotal().getAmountMinor()).isEqualTo(50000);
    assertThat(o.getHotel().getName()).isEqualTo("Harbor Suites");
    assertThat(o.getHotel().getNights()).isEqualTo(2);
    assertThat(o.getHotel().getNightlyRate().getAmountMinor()).isEqualTo(25000);
    assertThat(o.getHotel().getCity()).isEqualTo("SEA");
    assertThat(o.getHotel().getTimeZone()).isEqualTo("America/Los_Angeles");
    assertThat(o.getRefundable()).as("free cancellation until 2026-10-04").isTrue();
    assertThat(o.getCancellation().getPenalty().getAmountMinor()).isEqualTo(25000);
    assertThat(o.getExpiresAt().getSeconds())
        .isEqualTo(NOW.plus(HotelbedsHotelSupplier.RATE_KEY_LIFETIME).getEpochSecond());
  }

  @Test
  void anUnknownCityIsRefusedWithoutCallingTheSupplier() {
    assertThatThrownBy(
            () ->
                hotelbeds.searchHotels(
                    SearchHotelsRequest.newBuilder()
                        .setCtx(ctx())
                        .setCity("XXX")
                        .setCheckInDate("2026-10-06")
                        .setCheckOutDate("2026-10-08")
                        .build()))
        .isInstanceOf(SupplierException.class)
        .extracting(e -> ((SupplierException) e).code())
        .isEqualTo("UNKNOWN_LOCATION");
    server.verify();
  }

  @Test
  void bookingCarriesOurKeyAsClientReferenceAndCanBeFoundByIt() {
    String key = "ord_01ARZ3NDEKTSV4RRFFQ69G5FAV:itm_01ARZ3NDEKTSV4RRFFQ69G5FAW";
    String reference = HotelbedsHotelSupplier.clientReference(key);
    assertThat(reference).hasSize(20).matches("[0-9A-F]{20}");
    server
        .expect(requestTo("https://api.test.hotelbeds.example/hotel-api/1.0/bookings"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(jsonPath("$.holder.name").value("Alice"))
        .andExpect(jsonPath("$.holder.surname").value("Nguyen"))
        .andExpect(jsonPath("$.rooms[0].rateKey").value("RATEKEY"))
        .andExpect(jsonPath("$.rooms[0].paxes[0].type").value("AD"))
        .andExpect(jsonPath("$.clientReference").value(reference))
        .andRespond(withSuccess(BOOKING, MediaType.APPLICATION_JSON));
    CreateOrderResponse created =
        hotelbeds.createOrder(
            CreateOrderRequest.newBuilder()
                .setCtx(ctx().toBuilder().setIdempotencyKey(key))
                .setProviderOfferId("RATEKEY")
                .addPassengers(
                    Passenger.newBuilder()
                        .setGivenName("Alice")
                        .setFamilyName("Nguyen")
                        .setEmail("alice@acme.example"))
                .build());
    assertThat(created.getExternalOrderId()).isEqualTo("1-2345678");
    assertThat(created.getStatus()).isEqualTo(SupplierOrderStatus.CONFIRMED);
    assertThat(created.getCharged().getAmountMinor()).isEqualTo(50000);
    server.verify();

    server.reset();
    server
        .expect(
            requestTo(
                "https://api.test.hotelbeds.example/hotel-api/1.0/bookings?clientReference="
                    + reference
                    + "&filterType=CREATION&start=2026-09-21&end=2026-09-25"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(
            withSuccess(
                "{\"bookings\":{\"bookings\":["
                    + BOOKING.substring(BOOKING.indexOf('{', 1), BOOKING.lastIndexOf('}'))
                    + "]}}",
                MediaType.APPLICATION_JSON));
    BookingStatus found =
        hotelbeds.bookingStatus(
            GetBookingStatusRequest.newBuilder().setCtx(ctx()).setIdempotencyKey(key).build());
    assertThat(found.getStatus()).isEqualTo(SupplierOrderStatus.CONFIRMED);
    assertThat(found.getExternalOrderId()).isEqualTo("1-2345678");
    server.verify();
  }

  @Test
  void cancellationReportsTheRefundAsPaidMinusWhatTheHotelKeeps() {
    server
        .expect(requestTo("https://api.test.hotelbeds.example/hotel-api/1.0/bookings/1-2345678"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess(BOOKING, MediaType.APPLICATION_JSON));
    server
        .expect(
            requestTo(
                "https://api.test.hotelbeds.example/hotel-api/1.0/bookings/1-2345678?cancellationFlag=CANCELLATION"))
        .andExpect(method(HttpMethod.DELETE))
        .andRespond(
            withSuccess(
                BOOKING
                    .replace("\"CONFIRMED\"", "\"CANCELLED\"")
                    .replace("\"500.00\"", "\"250.00\""),
                MediaType.APPLICATION_JSON));
    CancelOrderResponse cancelled =
        hotelbeds.cancelOrder(
            CancelOrderRequest.newBuilder().setCtx(ctx()).setExternalOrderId("1-2345678").build());
    server.verify();
    assertThat(cancelled.getStatus()).isEqualTo(SupplierOrderStatus.CANCELLED);
    assertThat(cancelled.getRefund().getAmountMinor()).isEqualTo(25000);
  }

  @Test
  void rejectedCredentialsAreAFinalFailureNotARetry() {
    server
        .expect(requestTo("https://api.test.hotelbeds.example/hotel-api/1.0/hotels"))
        .andRespond(
            withStatus(HttpStatus.FORBIDDEN)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":{\"code\":\"INVALID_KEY\",\"message\":\"Invalid API key\"}}"));
    assertThatThrownBy(
            () ->
                hotelbeds.searchHotels(
                    SearchHotelsRequest.newBuilder()
                        .setCtx(ctx())
                        .setCity("SEA")
                        .setCheckInDate("2026-10-06")
                        .setCheckOutDate("2026-10-08")
                        .build()))
        .isInstanceOf(SupplierException.class)
        .satisfies(
            e -> {
              assertThat(((SupplierException) e).code()).isEqualTo("CREDENTIALS_REJECTED");
              assertThat(((SupplierException) e).retryable()).isFalse();
            });
  }

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

  private static final String AVAILABILITY =
      """
      {"hotels":{"checkIn":"2026-10-06","checkOut":"2026-10-08","total":1,"hotels":[
        {"code":12345,"name":"Harbor Suites","categoryName":"4 STARS","destinationName":"Seattle","latitude":"47.60","longitude":"-122.33","currency":"USD",
         "rooms":[{"code":"DBL.ST","name":"DOUBLE STANDARD","rates":[
            {"rateKey":"20261006|20261008|W|1|12345|DBL.ST|NRF-ALL|RO||1~1~0||N@06~~2a1b3~-1~~~","rateClass":"NOR","rateType":"BOOKABLE","net":"500.00","paymentType":"AT_WEB","packaging":false,
             "cancellationPolicies":[{"amount":"250.00","from":"2026-10-04T23:59:00-07:00"}],"rooms":1,"adults":1,"children":0},
            {"rateKey":"RECHECK-KEY","rateClass":"NOR","rateType":"RECHECK","net":"480.00","cancellationPolicies":[],"rooms":1,"adults":1,"children":0}
         ]}]}]}}
      """;

  private static final String BOOKING =
      """
      {"booking":{"reference":"1-2345678","clientReference":"X","creationDate":"2026-09-24","status":"CONFIRMED","currency":"USD","totalNet":"500.00",
        "hotel":{"code":12345,"name":"Harbor Suites","checkIn":"2026-10-06","checkOut":"2026-10-08"}}}
      """;
}
