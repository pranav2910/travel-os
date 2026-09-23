package io.travelos.common.geo;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Where an IATA code is on the clock. Nights, pickup times and deadlines are local facts; every
 * instant the platform stores is UTC, so the zone is what turns one into the other (ADR-0011).
 *
 * <p>This table is the platform's authoritative location catalog: a request may name only airports
 * listed here, on every path (a round trip and an itinerary alike), and the sandbox suppliers
 * refuse anything else. An unknown code is an explicit {@link Optional#empty()}, never a silent
 * UTC. City codes (NYC, LON, PAR, ...) are not airports: {@link #explainUnknown} tells the
 * requester which airports they resolve to.
 */
public final class Locations {
  private static final Map<String, String> ZONES =
      Map.ofEntries(
          // United States, Canada, Mexico, Caribbean
          Map.entry("BOS", "America/New_York"),
          Map.entry("BGR", "America/New_York"),
          Map.entry("PWM", "America/New_York"),
          Map.entry("JFK", "America/New_York"),
          Map.entry("EWR", "America/New_York"),
          Map.entry("LGA", "America/New_York"),
          Map.entry("DCA", "America/New_York"),
          Map.entry("IAD", "America/New_York"),
          Map.entry("BWI", "America/New_York"),
          Map.entry("PHL", "America/New_York"),
          Map.entry("ATL", "America/New_York"),
          Map.entry("CLT", "America/New_York"),
          Map.entry("MIA", "America/New_York"),
          Map.entry("FLL", "America/New_York"),
          Map.entry("MCO", "America/New_York"),
          Map.entry("TPA", "America/New_York"),
          Map.entry("DTW", "America/New_York"),
          Map.entry("ORD", "America/Chicago"),
          Map.entry("MDW", "America/Chicago"),
          Map.entry("DFW", "America/Chicago"),
          Map.entry("IAH", "America/Chicago"),
          Map.entry("AUS", "America/Chicago"),
          Map.entry("MSP", "America/Chicago"),
          Map.entry("STL", "America/Chicago"),
          Map.entry("MSY", "America/Chicago"),
          Map.entry("DEN", "America/Denver"),
          Map.entry("SLC", "America/Denver"),
          Map.entry("PHX", "America/Phoenix"),
          Map.entry("LAS", "America/Los_Angeles"),
          Map.entry("SEA", "America/Los_Angeles"),
          Map.entry("SFO", "America/Los_Angeles"),
          Map.entry("SJC", "America/Los_Angeles"),
          Map.entry("OAK", "America/Los_Angeles"),
          Map.entry("LAX", "America/Los_Angeles"),
          Map.entry("SAN", "America/Los_Angeles"),
          Map.entry("PDX", "America/Los_Angeles"),
          Map.entry("ANC", "America/Anchorage"),
          Map.entry("HNL", "Pacific/Honolulu"),
          Map.entry("YYZ", "America/Toronto"),
          Map.entry("YUL", "America/Toronto"),
          Map.entry("YVR", "America/Vancouver"),
          Map.entry("MEX", "America/Mexico_City"),
          Map.entry("CUN", "America/Cancun"),
          // Europe, Middle East
          Map.entry("LHR", "Europe/London"),
          Map.entry("LGW", "Europe/London"),
          Map.entry("DUB", "Europe/Dublin"),
          Map.entry("CDG", "Europe/Paris"),
          Map.entry("ORY", "Europe/Paris"),
          Map.entry("AMS", "Europe/Amsterdam"),
          Map.entry("BRU", "Europe/Brussels"),
          Map.entry("FRA", "Europe/Berlin"),
          Map.entry("MUC", "Europe/Berlin"),
          Map.entry("BER", "Europe/Berlin"),
          Map.entry("ZRH", "Europe/Zurich"),
          Map.entry("VIE", "Europe/Vienna"),
          Map.entry("MAD", "Europe/Madrid"),
          Map.entry("BCN", "Europe/Madrid"),
          Map.entry("LIS", "Europe/Lisbon"),
          Map.entry("FCO", "Europe/Rome"),
          Map.entry("MXP", "Europe/Rome"),
          Map.entry("CPH", "Europe/Copenhagen"),
          Map.entry("ARN", "Europe/Stockholm"),
          Map.entry("OSL", "Europe/Oslo"),
          Map.entry("HEL", "Europe/Helsinki"),
          Map.entry("WAW", "Europe/Warsaw"),
          Map.entry("ATH", "Europe/Athens"),
          Map.entry("IST", "Europe/Istanbul"),
          Map.entry("DXB", "Asia/Dubai"),
          Map.entry("AUH", "Asia/Dubai"),
          Map.entry("DOH", "Asia/Qatar"),
          Map.entry("TLV", "Asia/Jerusalem"),
          // Africa, Asia, Oceania, South America
          Map.entry("JNB", "Africa/Johannesburg"),
          Map.entry("CAI", "Africa/Cairo"),
          Map.entry("NBO", "Africa/Nairobi"),
          Map.entry("DEL", "Asia/Kolkata"),
          Map.entry("BOM", "Asia/Kolkata"),
          Map.entry("BLR", "Asia/Kolkata"),
          Map.entry("SIN", "Asia/Singapore"),
          Map.entry("KUL", "Asia/Kuala_Lumpur"),
          Map.entry("BKK", "Asia/Bangkok"),
          Map.entry("HKG", "Asia/Hong_Kong"),
          Map.entry("PVG", "Asia/Shanghai"),
          Map.entry("PEK", "Asia/Shanghai"),
          Map.entry("TPE", "Asia/Taipei"),
          Map.entry("ICN", "Asia/Seoul"),
          Map.entry("NRT", "Asia/Tokyo"),
          Map.entry("HND", "Asia/Tokyo"),
          Map.entry("KIX", "Asia/Tokyo"),
          Map.entry("SYD", "Australia/Sydney"),
          Map.entry("MEL", "Australia/Melbourne"),
          Map.entry("BNE", "Australia/Brisbane"),
          Map.entry("PER", "Australia/Perth"),
          Map.entry("AKL", "Pacific/Auckland"),
          Map.entry("GRU", "America/Sao_Paulo"),
          Map.entry("EZE", "America/Argentina/Buenos_Aires"),
          Map.entry("SCL", "America/Santiago"),
          Map.entry("BOG", "America/Bogota"),
          Map.entry("LIM", "America/Lima"),
          // the sandbox's simulated outage
          Map.entry("ZZZ", "UTC"));

  /**
   * City (metropolitan) codes are searches over several airports, which the platform does not do.
   */
  private static final Map<String, List<String>> METRO =
      Map.ofEntries(
          Map.entry("NYC", List.of("JFK", "EWR", "LGA")),
          Map.entry("WAS", List.of("DCA", "IAD", "BWI")),
          Map.entry("CHI", List.of("ORD", "MDW")),
          Map.entry("QSF", List.of("SFO", "SJC", "OAK")),
          Map.entry("YTO", List.of("YYZ")),
          Map.entry("YMQ", List.of("YUL")),
          Map.entry("LON", List.of("LHR", "LGW")),
          Map.entry("PAR", List.of("CDG", "ORY")),
          Map.entry("MIL", List.of("MXP")),
          Map.entry("ROM", List.of("FCO")),
          Map.entry("TYO", List.of("NRT", "HND")),
          Map.entry("OSA", List.of("KIX")),
          Map.entry("SEL", List.of("ICN")),
          Map.entry("BJS", List.of("PEK")),
          Map.entry("SHA", List.of("PVG")),
          Map.entry("BUE", List.of("EZE")),
          Map.entry("SAO", List.of("GRU")));

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

  public static boolean knows(String iata) {
    return zoneOf(iata).isPresent();
  }

  /** The airports a city code stands for, or empty when the code is not a known city either. */
  public static List<String> airportsOfCity(String code) {
    if (code == null) {
      return List.of();
    }
    return METRO.getOrDefault(code.trim().toUpperCase(Locale.ROOT), List.of());
  }

  /** The codes the platform cannot place on the clock, in request order (duplicates dropped). */
  public static List<String> unknown(List<String> codes) {
    List<String> out = new ArrayList<>();
    for (String c : codes) {
      if (c != null && !knows(c) && !out.contains(c)) {
        out.add(c);
      }
    }
    return out;
  }

  /**
   * Why a code is refused, in words the requester can act on: a city code names its airports; an
   * unknown code is said to be unknown.
   */
  public static String explainUnknown(String code) {
    List<String> airports = airportsOfCity(code);
    if (!airports.isEmpty()) {
      return code
          + " is a city, not an airport: use one of "
          + String.join(", ", airports)
          + " (the platform searches one airport per leg)";
    }
    return "unknown location " + code + ": the platform does not know its clock";
  }

  public static ZoneId zoneOrThrow(String iata) {
    return zoneOf(iata).orElseThrow(() -> new IllegalArgumentException(explainUnknown(iata)));
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
