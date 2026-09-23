package io.travelos.common.geo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

class LocationsTest {

  @Test
  void aRedEyeLandsOnTheNextLocalDate() {
    // 22:30 Pacific on 6 Oct is 05:30 UTC on 7 Oct; landing 06:45 Eastern on 7 Oct.
    Instant departs = Instant.parse("2026-10-07T05:30:00Z");
    Instant lands = Instant.parse("2026-10-07T10:45:00Z");
    assertThat(Locations.localDate(departs, Locations.zoneOrThrow("SEA")))
        .isEqualTo(LocalDate.of(2026, 10, 6));
    assertThat(Locations.localDate(lands, Locations.zoneOrThrow("BOS")))
        .isEqualTo(LocalDate.of(2026, 10, 7));
  }

  @Test
  void hotelClockIsLocal() {
    ZoneId seattle = Locations.zoneOrThrow("SEA");
    assertThat(Locations.checkIn(LocalDate.of(2026, 10, 6), seattle))
        .isEqualTo(Instant.parse("2026-10-06T22:00:00Z"));
    assertThat(Locations.checkOut(LocalDate.of(2026, 10, 8), seattle))
        .isEqualTo(Instant.parse("2026-10-08T18:00:00Z"));
    assertThat(Locations.nights(LocalDate.of(2026, 10, 6), LocalDate.of(2026, 10, 8))).isEqualTo(2);
    assertThat(Locations.nights(LocalDate.of(2026, 10, 8), LocalDate.of(2026, 10, 6))).isZero();
  }

  @Test
  void unknownPlacesAreExplicit() {
    assertThat(Locations.zoneOf("XXX")).isEmpty();
    assertThat(Locations.zoneOf(null)).isEmpty();
    assertThatThrownBy(() -> Locations.zoneOrThrow("XXX"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("XXX");
    assertThat(Locations.zoneOf(" sea ")).contains(ZoneId.of("America/Los_Angeles"));
  }

  @Test
  void theCatalogKnowsAirportsAndTheirClocksOnEveryPath() {
    // BUG-01 / BUG-10: one table for round trips and itineraries, wider than the audit's 24
    assertThat(Locations.zoneOf("lhr")).contains(ZoneId.of("Europe/London"));
    assertThat(Locations.zoneOf("FCO")).contains(ZoneId.of("Europe/Rome"));
    assertThat(Locations.zoneOf("SJC")).contains(ZoneId.of("America/Los_Angeles"));
    assertThat(Locations.zoneOf("BGR")).contains(ZoneId.of("America/New_York"));
    for (String code : List.of("NRT", "ICN", "SIN", "DXB", "DEL", "AKL", "HNL", "ANC", "GRU")) {
      assertThat(Locations.knows(code)).as(code).isTrue();
    }
    assertThat(Locations.knows("QQQ")).isFalse();
  }

  @Test
  void cityCodesAreNotAirportsButNameTheirs() {
    assertThat(Locations.knows("NYC")).isFalse();
    assertThat(Locations.airportsOfCity("NYC")).containsExactly("JFK", "EWR", "LGA");
    assertThat(Locations.airportsOfCity("LON")).containsExactly("LHR", "LGW");
    assertThat(Locations.airportsOfCity("SEA")).isEmpty();
    assertThat(Locations.explainUnknown("NYC"))
        .contains("NYC is a city, not an airport")
        .contains("JFK, EWR, LGA");
    assertThat(Locations.explainUnknown("QQQ")).contains("unknown location QQQ");
  }

  @Test
  void unknownCodesAreListedOnceInRequestOrder() {
    assertThat(Locations.unknown(List.of("BOS", "QQQ", "NYC", "QQQ", "SEA")))
        .containsExactly("QQQ", "NYC");
  }
}
