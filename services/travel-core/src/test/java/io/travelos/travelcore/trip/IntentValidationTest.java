package io.travelos.travelcore.trip;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * BUG-01 / BUG-03 / BUG-05 regressions: what a request must satisfy before it is accepted, judged
 * against a fixed clock. 2026-09-23T14:00Z is 10:00 in Boston and 07:00 in Seattle.
 */
class IntentValidationTest {

  private static final Instant NOW = Instant.parse("2026-09-23T14:00:00Z");

  @Test
  void anyThreeLettersIsNotAnAirport() {
    assertThatThrownBy(() -> IntentValidation.check(roundTrip("QQQ", "SEA"), NOW))
        .isInstanceOfSatisfying(
            IntentRejectedException.class,
            e -> {
              org.assertj.core.api.Assertions.assertThat(e.code()).isEqualTo("UNKNOWN_LOCATION");
              org.assertj.core.api.Assertions.assertThat(e.getMessage()).contains("QQQ");
            });
    assertThatThrownBy(() -> IntentValidation.check(roundTrip("BOS", "ZQX"), NOW))
        .isInstanceOfSatisfying(
            IntentRejectedException.class,
            e ->
                org.assertj.core.api.Assertions.assertThat(e.code()).isEqualTo("UNKNOWN_LOCATION"));
  }

  @Test
  void aCityCodeNamesTheAirportsItStandsFor() {
    assertThatThrownBy(() -> IntentValidation.check(roundTrip("BOS", "NYC"), NOW))
        .isInstanceOfSatisfying(
            IntentRejectedException.class,
            e -> {
              org.assertj.core.api.Assertions.assertThat(e.code()).isEqualTo("UNKNOWN_LOCATION");
              org.assertj.core.api.Assertions.assertThat(e.getMessage())
                  .contains("NYC is a city, not an airport")
                  .contains("JFK, EWR, LGA");
            });
  }

  @Test
  void everyLegOfAnItineraryIsCheckedAgainstTheCatalog() {
    assertThatThrownBy(
            () ->
                Itinerary.of(
                    List.of(
                        leg("BOS", "ORD", NOW.plus(Duration.ofDays(10))),
                        leg("ORD", "LON", NOW.plus(Duration.ofDays(12)))),
                    List.of(),
                    List.of(),
                    "USD"))
        .isInstanceOfSatisfying(
            IntentRejectedException.class,
            e -> {
              org.assertj.core.api.Assertions.assertThat(e.code()).isEqualTo("UNKNOWN_LOCATION");
              org.assertj.core.api.Assertions.assertThat(e.getMessage()).contains("LHR, LGW");
            });
  }

  @Test
  void yesterdayIsRefusedInTheDeparturesLocalTime() {
    // the window closed 2026-09-22 at 17:00Z = 13:00 in Boston
    TravelIntent yesterday =
        new TravelIntent(
            "BOS",
            "SEA",
            Instant.parse("2026-09-22T10:00:00Z"),
            Instant.parse("2026-09-22T17:00:00Z"),
            null,
            null,
            null,
            false,
            1);
    assertThatThrownBy(() -> IntentValidation.check(yesterday, NOW))
        .isInstanceOfSatisfying(
            IntentRejectedException.class,
            e -> {
              org.assertj.core.api.Assertions.assertThat(e.code()).isEqualTo("DEPARTURE_IN_PAST");
              org.assertj.core.api.Assertions.assertThat(e.getMessage())
                  .contains("2026-09-22 13:00 America/New_York")
                  .contains("2026-09-23 10:00 America/New_York");
            });
  }

  @Test
  void laterTodayIsFineEvenWhenTheWindowOpenedEarlier() {
    TravelIntent today =
        new TravelIntent(
            "BOS",
            "SEA",
            NOW.minus(Duration.ofHours(4)),
            NOW.plus(Duration.ofHours(6)),
            null,
            null,
            null,
            false,
            1);
    assertThatCode(() -> IntentValidation.check(today, NOW)).doesNotThrowAnyException();
  }

  @Test
  void aDeadlineAtOrBeforeNowIsClosed() {
    TravelIntent atNow =
        new TravelIntent(
            "BOS", "SEA", NOW.minus(Duration.ofHours(4)), NOW, null, null, null, false, 1);
    assertThatThrownBy(() -> IntentValidation.check(atNow, NOW))
        .isInstanceOfSatisfying(
            IntentRejectedException.class,
            e ->
                org.assertj.core.api.Assertions.assertThat(e.code())
                    .isEqualTo("DEPARTURE_IN_PAST"));
    TravelIntent oneSecondLater =
        new TravelIntent(
            "BOS",
            "SEA",
            NOW.minus(Duration.ofHours(4)),
            NOW.plusSeconds(1),
            null,
            null,
            null,
            false,
            1);
    assertThatCode(() -> IntentValidation.check(oneSecondLater, NOW)).doesNotThrowAnyException();
  }

  @Test
  void midnightIsJudgedOnTheDeparturesClockNotUtc() {
    // 2026-09-23T03:30Z is still 2026-09-22 23:30 in Boston: a "today" window there that closed
    // at local midnight is 04:00Z, and at 03:30Z it is still open
    Instant now = Instant.parse("2026-09-23T03:30:00Z");
    TravelIntent closesAtLocalMidnight =
        new TravelIntent(
            "BOS",
            "SEA",
            Instant.parse("2026-09-22T12:00:00Z"),
            Instant.parse("2026-09-23T04:00:00Z"),
            null,
            null,
            null,
            false,
            1);
    assertThatCode(() -> IntentValidation.check(closesAtLocalMidnight, now))
        .doesNotThrowAnyException();
    // ... while a window that closed at 2026-09-23T03:00Z (23:00 Boston) is the past, and the
    // message says so in Boston time
    TravelIntent closedAtEleven =
        new TravelIntent(
            "BOS",
            "SEA",
            Instant.parse("2026-09-22T12:00:00Z"),
            Instant.parse("2026-09-23T03:00:00Z"),
            null,
            null,
            null,
            false,
            1);
    assertThatThrownBy(() -> IntentValidation.check(closedAtEleven, now))
        .isInstanceOfSatisfying(
            IntentRejectedException.class,
            e ->
                org.assertj.core.api.Assertions.assertThat(e.getMessage())
                    .contains("2026-09-22 23:00 America/New_York"));
  }

  @Test
  void aReturnWindowThatClosedIsThePastToo() {
    TravelIntent returned =
        new TravelIntent(
            "BOS",
            "SEA",
            NOW.minus(Duration.ofDays(3)),
            NOW.plus(Duration.ofHours(1)),
            NOW.plus(Duration.ofHours(2)),
            NOW.plus(Duration.ofHours(3)),
            null,
            false,
            1);
    assertThatCode(() -> IntentValidation.check(returned, NOW)).doesNotThrowAnyException();
  }

  @Test
  void aStayThatAlreadyEndedIsRefused() {
    Itinerary it =
        Itinerary.of(
            List.of(
                leg("BOS", "SEA", NOW.plus(Duration.ofHours(6))),
                leg("SEA", "BOS", NOW.plus(Duration.ofDays(3)))),
            List.of(
                new Itinerary.StaySpec(
                    null,
                    "SEA",
                    LocalDate.parse("2026-09-23"),
                    LocalDate.parse("2026-09-25"),
                    true)),
            List.of(),
            "USD");
    assertThatCode(() -> IntentValidation.check(TravelIntent.of(it, null, 1), NOW))
        .doesNotThrowAnyException();
    // check-out 2026-09-25 11:00 Seattle = 18:00Z; at 19:00Z that day the stay is over
    assertThatThrownBy(
            () ->
                IntentValidation.check(
                    TravelIntent.of(it, null, 1), Instant.parse("2026-09-25T19:00:00Z")))
        .isInstanceOfSatisfying(
            IntentRejectedException.class,
            e ->
                org.assertj.core.api.Assertions.assertThat(e.code())
                    .isIn("DEPARTURE_IN_PAST", "STAY_IN_PAST"));
  }

  @Test
  void onlyCurrenciesTheSuppliersQuoteAreAccepted() {
    List<Itinerary.LegSpec> legs =
        List.of(
            leg("BOS", "SEA", NOW.plus(Duration.ofDays(10))),
            leg("SEA", "BOS", NOW.plus(Duration.ofDays(12))));
    Itinerary usd = Itinerary.of(legs, List.of(), List.of(), "USD");
    assertThatCode(() -> IntentValidation.check(TravelIntent.of(usd, null, 1), NOW))
        .doesNotThrowAnyException();
    // GBP is what a London hotel is quoted in, not what a whole trip can be priced in: the sandbox
    // airline quotes USD only and nothing converts, so a GBP trip could never fly
    for (String unsupported : List.of("XXX", "EUR", "JPY", "GBP")) {
      Itinerary it = Itinerary.of(legs, List.of(), List.of(), unsupported);
      assertThatThrownBy(() -> IntentValidation.check(TravelIntent.of(it, null, 1), NOW))
          .isInstanceOfSatisfying(
              IntentRejectedException.class,
              e -> {
                org.assertj.core.api.Assertions.assertThat(e.code())
                    .isEqualTo("CURRENCY_UNSUPPORTED");
                org.assertj.core.api.Assertions.assertThat(e.getMessage())
                    .contains(unsupported)
                    .contains("priced in USD");
              });
    }
  }

  private static TravelIntent roundTrip(String from, String to) {
    return new TravelIntent(
        from,
        to,
        NOW.plus(Duration.ofDays(10)),
        NOW.plus(Duration.ofDays(10)).plus(Duration.ofHours(8)),
        NOW.plus(Duration.ofDays(12)),
        NOW.plus(Duration.ofDays(12)).plus(Duration.ofHours(8)),
        "customer meeting",
        false,
        1);
  }

  private static Itinerary.LegSpec leg(String from, String to, Instant departure) {
    return new Itinerary.LegSpec(null, from, to, departure, departure.plus(Duration.ofHours(8)));
  }
}
