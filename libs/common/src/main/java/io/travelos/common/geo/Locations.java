package io.travelos.common.geo;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Where an IATA code is on the clock. Nights, pickup times and deadlines are local facts; every
 * instant the platform stores is UTC, so the zone is what turns one into the other (ADR-0011).
 *
 * <p>Slice 3 covers the airports the sandbox suppliers serve. An unknown code is an explicit {@link
 * Optional#empty()}, never a silent UTC: callers reject it.
 */
public final class Locations {
  private static final Map<String, String> ZONES =
      Map.ofEntries(
          Map.entry("BOS", "America/New_York"),
          Map.entry("JFK", "America/New_York"),
          Map.entry("EWR", "America/New_York"),
          Map.entry("LGA", "America/New_York"),
          Map.entry("DCA", "America/New_York"),
          Map.entry("IAD", "America/New_York"),
          Map.entry("ATL", "America/New_York"),
          Map.entry("MIA", "America/New_York"),
          Map.entry("ORD", "America/Chicago"),
          Map.entry("DFW", "America/Chicago"),
          Map.entry("AUS", "America/Chicago"),
          Map.entry("MSP", "America/Chicago"),
          Map.entry("DEN", "America/Denver"),
          Map.entry("PHX", "America/Phoenix"),
          Map.entry("SEA", "America/Los_Angeles"),
          Map.entry("SFO", "America/Los_Angeles"),
          Map.entry("LAX", "America/Los_Angeles"),
          Map.entry("SAN", "America/Los_Angeles"),
          Map.entry("PDX", "America/Los_Angeles"),
          Map.entry("YYZ", "America/Toronto"),
          Map.entry("YVR", "America/Vancouver"),
          Map.entry("LHR", "Europe/London"),
          Map.entry("CDG", "Europe/Paris"),
          Map.entry("FRA", "Europe/Berlin"),
          Map.entry("ZZZ", "UTC"));

  /** Hotels hand over rooms at 15:00 and want them back by 11:00, local time. */
  public static final LocalTime HOTEL_CHECK_IN = LocalTime.of(15, 0);

  public static final LocalTime HOTEL_CHECK_OUT = LocalTime.of(11, 0);

  private Locations() {}

  public static Optional<ZoneId> zoneOf(String iata) {
    if (iata == null) {
      return Optional.empty();
    }
    String code = iata.trim().toUpperCase(Locale.ROOT);
    return Optional.ofNullable(ZONES.get(code)).map(ZoneId::of);
  }

  public static ZoneId zoneOrThrow(String iata) {
    return zoneOf(iata)
        .orElseThrow(() -> new IllegalArgumentException("unknown location: " + iata));
  }

  /** The local calendar date of an instant at a location: a red-eye lands on the next date. */
  public static LocalDate localDate(Instant at, ZoneId zone) {
    return at.atZone(zone).toLocalDate();
  }

  public static Instant checkIn(LocalDate date, ZoneId zone) {
    return LocalDateTime.of(date, HOTEL_CHECK_IN).atZone(zone).toInstant();
  }

  public static Instant checkOut(LocalDate date, ZoneId zone) {
    return LocalDateTime.of(date, HOTEL_CHECK_OUT).atZone(zone).toInstant();
  }

  /** Nights between two local dates; never negative. */
  public static int nights(LocalDate checkIn, LocalDate checkOut) {
    long days = checkOut.toEpochDay() - checkIn.toEpochDay();
    return (int) Math.max(0, days);
  }
}
