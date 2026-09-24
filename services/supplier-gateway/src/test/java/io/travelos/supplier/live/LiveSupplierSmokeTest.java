package io.travelos.supplier.live;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.Timestamp;
import io.travelos.contracts.common.v1.Cabin;
import io.travelos.contracts.common.v1.RequestContext;
import io.travelos.contracts.common.v1.TimeWindow;
import io.travelos.contracts.supplier.v1.SearchAirRequest;
import io.travelos.contracts.supplier.v1.SearchAirResponse;
import io.travelos.contracts.supplier.v1.SearchHotelsRequest;
import io.travelos.contracts.supplier.v1.SearchHotelsResponse;
import io.travelos.supplier.live.duffel.DuffelAirSupplier;
import io.travelos.supplier.live.duffel.DuffelClient;
import io.travelos.supplier.live.hotelbeds.HotelbedsClient;
import io.travelos.supplier.live.hotelbeds.HotelbedsHotelSupplier;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.client.RestClient;

/**
 * Credential-gated smoke tests against the suppliers' TEST environments: a search only, never a
 * booking (a booking would create a real reservation, which this repository never does from a
 * test). Skipped unless the credentials are in the environment; when they run, their result is a
 * verified live integration of search; nothing else is claimed.
 */
class LiveSupplierSmokeTest {

  @Test
  @EnabledIfEnvironmentVariable(named = "DUFFEL_ACCESS_TOKEN", matches = "duffel_test_.+")
  void duffelTestEnvironmentAnswersASearch() {
    LiveIntegrationProperties.Duffel props =
        new LiveIntegrationProperties.Duffel(
            System.getenv("DUFFEL_ACCESS_TOKEN"), null, null, Map.of());
    DuffelAirSupplier duffel =
        new DuffelAirSupplier(
            new DuffelClient(RestClient.builder(), props), props, Clock.systemUTC());
    LocalDate date = LocalDate.now(ZoneOffset.UTC).plusDays(45);
    Instant start = date.atStartOfDay(ZoneOffset.UTC).toInstant();
    SearchAirResponse response =
        duffel.search(
            SearchAirRequest.newBuilder()
                .setCtx(ctx())
                .setOrigin("LHR")
                .setDestination("JFK")
                .setPassengers(1)
                .addCabins(Cabin.ECONOMY)
                .setOutboundDeparture(
                    TimeWindow.newBuilder()
                        .setNotBefore(ts(start))
                        .setNotAfter(ts(start.plusSeconds(86_000))))
                .build());
    assertThat(response.getOffersList()).isNotEmpty();
    assertThat(response.getOffers(0).getProvider()).isEqualTo("duffel");
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "HOTELBEDS_API_KEY", matches = ".+")
  void hotelbedsTestEnvironmentAnswersASearch() {
    LiveIntegrationProperties.Hotelbeds props =
        new LiveIntegrationProperties.Hotelbeds(
            System.getenv("HOTELBEDS_API_KEY"), System.getenv("HOTELBEDS_SECRET"), null, null, 25);
    Clock clock = Clock.systemUTC();
    HotelbedsHotelSupplier hotelbeds =
        new HotelbedsHotelSupplier(
            new HotelbedsClient(RestClient.builder(), props, clock), props, clock);
    LocalDate in = LocalDate.now(ZoneOffset.UTC).plusDays(45);
    SearchHotelsResponse response =
        hotelbeds.searchHotels(
            SearchHotelsRequest.newBuilder()
                .setCtx(ctx())
                .setCity("MAD")
                .setCheckInDate(in.toString())
                .setCheckOutDate(in.plusDays(2).toString())
                .setGuests(1)
                .build());
    assertThat(response.getOffersList()).isNotEmpty();
  }

  private static RequestContext ctx() {
    return RequestContext.newBuilder()
        .setTenantId("acme")
        .setCorrelationId("trip_01ARZ3NDEKTSV4RRFFQ69G5FAV")
        .setPrincipal(
            io.travelos.contracts.common.v1.Principal.newBuilder()
                .setKind(io.travelos.contracts.common.v1.Principal.Kind.SERVICE)
                .setId("service/test"))
        .build();
  }

  private static Timestamp ts(Instant i) {
    return Timestamp.newBuilder().setSeconds(i.getEpochSecond()).build();
  }
}
