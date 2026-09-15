package io.travelos.travelcore.trip;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class ItineraryTest {

  private static final Instant D1 = Instant.parse("2026-10-06T10:00:00Z");
  private static final Instant A1 = Instant.parse("2026-10-06T23:00:00Z");
  private static final Instant D2 = Instant.parse("2026-10-08T15:00:00Z");
  private static final Instant A2 = Instant.parse("2026-10-08T22:00:00Z");
  private static final Instant D3 = Instant.parse("2026-10-09T13:00:00Z");
  private static final Instant A3 = Instant.parse("2026-10-10T04:00:00Z");

  private static Itinerary threeCities() {
    return Itinerary.of(
        List.of(
            new Itinerary.LegSpec(null, "BOS", "SEA", D1, A1),
            new Itinerary.LegSpec(null, "SEA", "SFO", D2, A2),
            new Itinerary.LegSpec(null, "SFO", "BOS", D3, A3)),
        List.of(
            new Itinerary.StaySpec(
                null, "SEA", LocalDate.of(2026, 10, 6), LocalDate.of(2026, 10, 8), true),
            new Itinerary.StaySpec(
                null, "SFO", LocalDate.of(2026, 10, 8), LocalDate.of(2026, 10, 9), true)),
        List.of(
            new Itinerary.TransferSpec(null, "AIRPORT_TO_HOTEL", "SEA", null, null, null, true),
            new Itinerary.TransferSpec(null, "HOTEL_TO_AIRPORT", "SFO", null, null, null, false)),
        null);
  }

  @Test
  void componentsGetStableIdsZonesAndDependencies() {
    Itinerary it = threeCities();
    assertThat(it.componentIds()).hasSize(7).allMatch(id -> id.startsWith("cmp_"));
    assertThat(it.legs().get(1).dependsOn()).containsExactly(it.legs().get(0).componentId());
    assertThat(it.legs().get(0).originZone().getId()).isEqualTo("America/New_York");
    assertThat(it.legs().get(0).destinationZone().getId()).isEqualTo("America/Los_Angeles");
    Itinerary.Stay seattle = it.stays().get(0);
    assertThat(seattle.nights()).isEqualTo(2);
    assertThat(seattle.arrivalLeg()).contains(it.legs().get(0).componentId());
    assertThat(seattle.departureLeg()).contains(it.legs().get(1).componentId());
    Itinerary.Transfer toHotel = it.transfers().get(0);
    assertThat(toHotel.leg()).contains(it.legs().get(0).componentId());
    assertThat(toHotel.from()).isEqualTo("SEA airport");
    Itinerary.Transfer toAirport = it.transfers().get(1);
    assertThat(toAirport.leg()).contains(it.legs().get(2).componentId());
    assertThat(toAirport.required()).isFalse();
    assertThat(it.returnsHome()).isTrue();
    assertThat(it.currency()).isEqualTo("USD");
  }

  @Test
  void theLegacyIntentDescribesTheFirstLegAndTheReturn() {
    TravelIntent intent = TravelIntent.of(threeCities(), "roadshow", 1);
    assertThat(intent.origin()).isEqualTo("BOS");
    assertThat(intent.destination()).isEqualTo("SEA");
    assertThat(intent.earliestDeparture()).isEqualTo(D1);
    assertThat(intent.arrivalDeadline()).isEqualTo(A1);
    assertThat(intent.returnAfter()).isEqualTo(D3);
    assertThat(intent.latestReturn()).isEqualTo(A3);
    assertThat(intent.hotelRequired()).isTrue();
    assertThat(intent.isItinerary()).isTrue();
    assertThat(intent.isRoundTrip()).isTrue();
    // an open jaw has no "return"
    TravelIntent openJaw =
        TravelIntent.of(
            Itinerary.of(
                List.of(new Itinerary.LegSpec(null, "BOS", "SEA", D1, A1)),
                List.of(),
                List.of(),
                null),
            null,
            1);
    assertThat(openJaw.returnAfter()).isNull();
    assertThat(openJaw.isRoundTrip()).isFalse();
  }

  @Test
  void aRedEyeLandsOnTheNextLocalDateAndTheStayFollowsIt() {
    // 22:30 Pacific on 6 Oct (05:30Z on the 7th) landing 06:45 Eastern on 7 Oct: the Boston stay
    // starts on the 7th, and the leg that brings the traveler there is found by local date.
    Itinerary it =
        Itinerary.of(
            List.of(
                new Itinerary.LegSpec(null, "BOS", "SEA", D1, A1),
                new Itinerary.LegSpec(
                    null,
                    "SEA",
                    "BOS",
                    Instant.parse("2026-10-07T05:30:00Z"),
                    Instant.parse("2026-10-07T11:00:00Z"))),
            List.of(
                new Itinerary.StaySpec(
                    null, "BOS", LocalDate.of(2026, 10, 7), LocalDate.of(2026, 10, 8), true)),
            List.of(),
            "USD");
    assertThat(it.stays().get(0).arrivalLeg()).contains(it.legs().get(1).componentId());
    assertThat(it.stays().get(0).zone().getId()).isEqualTo("America/New_York");
  }

  @Test
  void roundTripsSurviveTheJsonCodec() {
    Itinerary it = threeCities();
    Itinerary back = ItineraryCodec.fromJson(ItineraryCodec.toJson(it));
    assertThat(back).isEqualTo(it);
    assertThat(ItineraryCodec.fromJson(null)).isNull();
  }

  @Test
  void rejectsWhatCannotBeFlownOrCountedOrPaidFor() {
    assertThatThrownBy(
            () ->
                Itinerary.of(
                    List.of(
                        new Itinerary.LegSpec(null, "BOS", "SEA", D1, A1),
                        new Itinerary.LegSpec(null, "SFO", "BOS", D2, A2)),
                    List.of(),
                    List.of(),
                    null))
        .hasMessageContaining("must depart from SEA");
    assertThatThrownBy(
            () ->
                Itinerary.of(
                    List.of(
                        new Itinerary.LegSpec(null, "BOS", "SEA", D2, A2),
                        new Itinerary.LegSpec(null, "SEA", "BOS", D1, A1)),
                    List.of(),
                    List.of(),
                    null))
        .hasMessageContaining("must come after");
    assertThatThrownBy(
            () ->
                Itinerary.of(
                    List.of(new Itinerary.LegSpec(null, "BOS", "SEA", D1, A1)),
                    List.of(
                        new Itinerary.StaySpec(
                            null,
                            "SFO",
                            LocalDate.of(2026, 10, 6),
                            LocalDate.of(2026, 10, 7),
                            true)),
                    List.of(),
                    null))
        .hasMessageContaining("no leg lands there");
    assertThatThrownBy(
            () ->
                Itinerary.of(
                    List.of(new Itinerary.LegSpec(null, "BOS", "SEA", D1, A1)),
                    List.of(
                        new Itinerary.StaySpec(
                            null,
                            "SEA",
                            LocalDate.of(2026, 10, 7),
                            LocalDate.of(2026, 10, 7),
                            true)),
                    List.of(),
                    null))
        .hasMessageContaining("checkOut must follow checkIn");
    assertThatThrownBy(
            () ->
                Itinerary.of(
                    List.of(new Itinerary.LegSpec(null, "BOS", "XXX", D1, A1)),
                    List.of(),
                    List.of(),
                    null))
        .hasMessageContaining("unknown location XXX");
    assertThatThrownBy(
            () ->
                Itinerary.of(
                    List.of(new Itinerary.LegSpec(null, "BOS", "SEA", D1, A1)),
                    List.of(),
                    List.of(),
                    "DOLLARS"))
        .hasMessageContaining("ISO 4217");
    assertThatThrownBy(
            () ->
                Itinerary.of(
                    List.of(new Itinerary.LegSpec(null, "BOS", "SEA", D1, A1)),
                    List.of(),
                    List.of(
                        new Itinerary.TransferSpec(
                            null, "POINT_TO_POINT", "SEA", "a", "b", null, true)),
                    null))
        .hasMessageContaining("needs a pickup time");
    assertThatThrownBy(() -> Itinerary.of(List.of(), List.of(), List.of(), null))
        .hasMessageContaining("at least one leg");
  }
}
