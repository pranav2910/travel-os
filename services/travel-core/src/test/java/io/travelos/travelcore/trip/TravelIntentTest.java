package io.travelos.travelcore.trip;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class TravelIntentTest {

  private static final Instant T0 = Instant.parse("2026-10-06T10:00:00Z");

  @Test
  void roundTripIsValid() {
    TravelIntent intent =
        new TravelIntent(
            "BOS",
            "SEA",
            T0,
            T0.plusSeconds(6 * 3600),
            T0.plusSeconds(86400),
            T0.plusSeconds(2 * 86400),
            "customer meeting",
            true,
            1);
    assertThat(intent.isRoundTrip()).isTrue();
  }

  @Test
  void aHotelRequestWithUnambiguousNightsBecomesOneRequiredStay() {
    // lands by 23:00Z on the 6th (16:00 Pacific), returns after 13:00Z on the 8th: two nights
    TravelIntent legacy =
        new TravelIntent(
            "BOS",
            "SEA",
            Instant.parse("2026-10-06T10:00:00Z"),
            Instant.parse("2026-10-06T23:00:00Z"),
            Instant.parse("2026-10-08T13:00:00Z"),
            Instant.parse("2026-10-09T02:00:00Z"),
            "roadshow",
            true,
            1);
    TravelIntent explicit = legacy.withExplicitStay();
    assertThat(explicit.isItinerary()).isTrue();
    assertThat(explicit.hotelRequired()).isTrue();
    assertThat(explicit.itinerary().legs()).hasSize(2);
    assertThat(explicit.itinerary().stays()).hasSize(1);
    Itinerary.Stay stay = explicit.itinerary().stays().getFirst();
    assertThat(stay.city()).isEqualTo("SEA");
    assertThat(stay.checkIn()).isEqualTo(LocalDate.parse("2026-10-06"));
    assertThat(stay.checkOut()).isEqualTo(LocalDate.parse("2026-10-08"));
    assertThat(stay.required()).isTrue();
    assertThat(stay.arrivalLeg()).contains(explicit.itinerary().legs().getFirst().componentId());
    assertThat(stay.departureLeg()).contains(explicit.itinerary().legs().getLast().componentId());
    // the Slice 1/2 fields still describe the first leg and the return
    assertThat(explicit.origin()).isEqualTo("BOS");
    assertThat(explicit.returnAfter()).isEqualTo(legacy.returnAfter());
    // idempotent, and a request without the flag is untouched
    assertThat(explicit.withExplicitStay()).isSameAs(explicit);
    TravelIntent noHotel =
        new TravelIntent(
            "BOS",
            "SEA",
            legacy.earliestDeparture(),
            legacy.arrivalDeadline(),
            legacy.returnAfter(),
            legacy.latestReturn(),
            null,
            false,
            1);
    assertThat(noHotel.withExplicitStay()).isSameAs(noHotel);
  }

  @Test
  void aHotelRequestWhoseNightsAreAmbiguousIsRefusedWithAnActionableCode() {
    Instant out = Instant.parse("2026-10-06T10:00:00Z");
    Instant land = Instant.parse("2026-10-06T23:00:00Z");
    // no return window: which nights?
    assertThatThrownBy(
            () ->
                new TravelIntent("BOS", "SEA", out, land, null, null, null, true, 1)
                    .withExplicitStay())
        .isInstanceOf(TravelIntent.HotelRequestException.class)
        .hasMessageContaining("return window");
    // a same-day return: no night at all
    assertThatThrownBy(
            () ->
                new TravelIntent(
                        "BOS",
                        "SEA",
                        out,
                        land,
                        Instant.parse("2026-10-07T04:00:00Z"), // 21:00 Pacific on the 6th
                        Instant.parse("2026-10-07T06:00:00Z"),
                        null,
                        true,
                        1)
                    .withExplicitStay())
        .isInstanceOf(TravelIntent.HotelRequestException.class)
        .hasMessageContaining("no night to book");
    // an outbound window three days wide: the first night is anyone's guess
    assertThatThrownBy(
            () ->
                new TravelIntent(
                        "BOS",
                        "SEA",
                        out,
                        Instant.parse("2026-10-09T10:00:00Z"),
                        Instant.parse("2026-10-10T13:00:00Z"),
                        Instant.parse("2026-10-11T02:00:00Z"),
                        null,
                        true,
                        1)
                    .withExplicitStay())
        .isInstanceOf(TravelIntent.HotelRequestException.class)
        .hasMessageContaining("ambiguous");
    // a destination outside the catalog is refused as such, not as a hotel problem (BUG-01)
    assertThatThrownBy(
            () ->
                new TravelIntent(
                        "BOS",
                        "QQQ",
                        out,
                        land,
                        Instant.parse("2026-10-08T13:00:00Z"),
                        Instant.parse("2026-10-09T02:00:00Z"),
                        null,
                        true,
                        1)
                    .withExplicitStay())
        .isInstanceOfSatisfying(
            IntentRejectedException.class, e -> assertThat(e.code()).isEqualTo("UNKNOWN_LOCATION"))
        .hasMessageContaining("unknown location QQQ");
    assertThat(new TravelIntent.HotelRequestException("x").code())
        .isEqualTo("HOTEL_DETAILS_INSUFFICIENT");
  }

  @Test
  void oneWayIsValid() {
    assertThat(
            new TravelIntent("BOS", "SEA", T0, T0.plusSeconds(3600), null, null, null, false, 1)
                .isRoundTrip())
        .isFalse();
  }

  @Test
  void rejectsNonsense() {
    assertThatThrownBy(
            () -> new TravelIntent("bos", "SEA", T0, T0.plusSeconds(1), null, null, null, false, 1))
        .hasMessageContaining("IATA");
    assertThatThrownBy(
            () -> new TravelIntent("BOS", "BOS", T0, T0.plusSeconds(1), null, null, null, false, 1))
        .hasMessageContaining("differ");
    assertThatThrownBy(() -> new TravelIntent("BOS", "SEA", T0, T0, null, null, null, false, 1))
        .hasMessageContaining("before");
    assertThatThrownBy(
            () ->
                new TravelIntent(
                    "BOS", "SEA", T0, T0.plusSeconds(1), T0.plusSeconds(2), null, null, false, 1))
        .hasMessageContaining("together");
    assertThatThrownBy(
            () ->
                new TravelIntent(
                    "BOS",
                    "SEA",
                    T0,
                    T0.plusSeconds(10),
                    T0.plusSeconds(5),
                    T0.plusSeconds(20),
                    null,
                    false,
                    1))
        .hasMessageContaining("returnAfter");
    assertThatThrownBy(
            () -> new TravelIntent("BOS", "SEA", T0, T0.plusSeconds(1), null, null, null, false, 0))
        .hasMessageContaining("travelers");
  }
}
