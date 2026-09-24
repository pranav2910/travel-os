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

  /**
   * Phase 4: where an airport is, for suppliers that search by coordinates (hotels near the
   * airport's city) and for distance checks. Curated by hand for the catalog's airports; a fuller
   * dataset (OurAirports) can replace this map without changing callers.
   *
   * @param city the city the airport serves, in English
   * @param country ISO 3166-1 alpha-2
   */
  public record Place(
      String iata, String city, String country, double latitude, double longitude) {}

  private static final Map<String, Place> PLACES =
      Map.ofEntries(
          Map.entry("BOS", new Place("BOS", "Boston", "US", 42.364, -71.005)),
          Map.entry("BGR", new Place("BGR", "Bangor", "US", 44.807, -68.828)),
          Map.entry("PWM", new Place("PWM", "Portland", "US", 43.646, -70.309)),
          Map.entry("JFK", new Place("JFK", "New York", "US", 40.640, -73.779)),
          Map.entry("EWR", new Place("EWR", "Newark", "US", 40.692, -74.169)),
          Map.entry("LGA", new Place("LGA", "New York", "US", 40.777, -73.874)),
          Map.entry("DCA", new Place("DCA", "Washington", "US", 38.852, -77.037)),
          Map.entry("IAD", new Place("IAD", "Washington", "US", 38.953, -77.456)),
          Map.entry("BWI", new Place("BWI", "Baltimore", "US", 39.175, -76.668)),
          Map.entry("PHL", new Place("PHL", "Philadelphia", "US", 39.872, -75.241)),
          Map.entry("ATL", new Place("ATL", "Atlanta", "US", 33.640, -84.427)),
          Map.entry("CLT", new Place("CLT", "Charlotte", "US", 35.214, -80.943)),
          Map.entry("MIA", new Place("MIA", "Miami", "US", 25.796, -80.287)),
          Map.entry("FLL", new Place("FLL", "Fort Lauderdale", "US", 26.072, -80.153)),
          Map.entry("MCO", new Place("MCO", "Orlando", "US", 28.429, -81.309)),
          Map.entry("TPA", new Place("TPA", "Tampa", "US", 27.976, -82.533)),
          Map.entry("DTW", new Place("DTW", "Detroit", "US", 42.212, -83.353)),
          Map.entry("ORD", new Place("ORD", "Chicago", "US", 41.978, -87.905)),
          Map.entry("MDW", new Place("MDW", "Chicago", "US", 41.786, -87.752)),
          Map.entry("DFW", new Place("DFW", "Dallas", "US", 32.897, -97.038)),
          Map.entry("IAH", new Place("IAH", "Houston", "US", 29.990, -95.337)),
          Map.entry("AUS", new Place("AUS", "Austin", "US", 30.194, -97.670)),
          Map.entry("MSP", new Place("MSP", "Minneapolis", "US", 44.882, -93.222)),
          Map.entry("STL", new Place("STL", "St Louis", "US", 38.749, -90.370)),
          Map.entry("MSY", new Place("MSY", "New Orleans", "US", 29.993, -90.258)),
          Map.entry("DEN", new Place("DEN", "Denver", "US", 39.856, -104.674)),
          Map.entry("SLC", new Place("SLC", "Salt Lake City", "US", 40.789, -111.978)),
          Map.entry("PHX", new Place("PHX", "Phoenix", "US", 33.434, -112.012)),
          Map.entry("LAS", new Place("LAS", "Las Vegas", "US", 36.084, -115.154)),
          Map.entry("SEA", new Place("SEA", "Seattle", "US", 47.450, -122.309)),
          Map.entry("SFO", new Place("SFO", "San Francisco", "US", 37.619, -122.375)),
          Map.entry("SJC", new Place("SJC", "San Jose", "US", 37.363, -121.929)),
          Map.entry("OAK", new Place("OAK", "Oakland", "US", 37.721, -122.221)),
          Map.entry("LAX", new Place("LAX", "Los Angeles", "US", 33.942, -118.408)),
          Map.entry("SAN", new Place("SAN", "San Diego", "US", 32.734, -117.190)),
          Map.entry("PDX", new Place("PDX", "Portland", "US", 45.589, -122.597)),
          Map.entry("ANC", new Place("ANC", "Anchorage", "US", 61.174, -149.996)),
          Map.entry("HNL", new Place("HNL", "Honolulu", "US", 21.319, -157.922)),
          Map.entry("YYZ", new Place("YYZ", "Toronto", "CA", 43.677, -79.631)),
          Map.entry("YUL", new Place("YUL", "Montreal", "CA", 45.470, -73.741)),
          Map.entry("YVR", new Place("YVR", "Vancouver", "CA", 49.195, -123.179)),
          Map.entry("MEX", new Place("MEX", "Mexico City", "MX", 19.436, -99.072)),
          Map.entry("CUN", new Place("CUN", "Cancun", "MX", 21.037, -86.877)),
          Map.entry("LHR", new Place("LHR", "London", "GB", 51.470, -0.454)),
          Map.entry("LGW", new Place("LGW", "London", "GB", 51.148, -0.190)),
          Map.entry("DUB", new Place("DUB", "Dublin", "IE", 53.421, -6.270)),
          Map.entry("CDG", new Place("CDG", "Paris", "FR", 49.010, 2.548)),
          Map.entry("ORY", new Place("ORY", "Paris", "FR", 48.723, 2.379)),
          Map.entry("AMS", new Place("AMS", "Amsterdam", "NL", 52.310, 4.768)),
          Map.entry("BRU", new Place("BRU", "Brussels", "BE", 50.901, 4.484)),
          Map.entry("FRA", new Place("FRA", "Frankfurt", "DE", 50.033, 8.571)),
          Map.entry("MUC", new Place("MUC", "Munich", "DE", 48.354, 11.786)),
          Map.entry("BER", new Place("BER", "Berlin", "DE", 52.362, 13.501)),
          Map.entry("ZRH", new Place("ZRH", "Zurich", "CH", 47.458, 8.555)),
          Map.entry("VIE", new Place("VIE", "Vienna", "AT", 48.110, 16.570)),
          Map.entry("MAD", new Place("MAD", "Madrid", "ES", 40.472, -3.561)),
          Map.entry("BCN", new Place("BCN", "Barcelona", "ES", 41.297, 2.078)),
          Map.entry("LIS", new Place("LIS", "Lisbon", "PT", 38.774, -9.134)),
          Map.entry("FCO", new Place("FCO", "Rome", "IT", 41.800, 12.239)),
          Map.entry("MXP", new Place("MXP", "Milan", "IT", 45.630, 8.723)),
          Map.entry("CPH", new Place("CPH", "Copenhagen", "DK", 55.618, 12.656)),
          Map.entry("ARN", new Place("ARN", "Stockholm", "SE", 59.650, 17.919)),
          Map.entry("OSL", new Place("OSL", "Oslo", "NO", 60.194, 11.100)),
          Map.entry("HEL", new Place("HEL", "Helsinki", "FI", 60.317, 24.963)),
          Map.entry("WAW", new Place("WAW", "Warsaw", "PL", 52.166, 20.967)),
          Map.entry("ATH", new Place("ATH", "Athens", "GR", 37.936, 23.945)),
          Map.entry("IST", new Place("IST", "Istanbul", "TR", 41.275, 28.752)),
          Map.entry("DXB", new Place("DXB", "Dubai", "AE", 25.253, 55.366)),
          Map.entry("AUH", new Place("AUH", "Abu Dhabi", "AE", 24.433, 54.651)),
          Map.entry("DOH", new Place("DOH", "Doha", "QA", 25.273, 51.608)),
          Map.entry("TLV", new Place("TLV", "Tel Aviv", "IL", 32.009, 34.883)),
          Map.entry("JNB", new Place("JNB", "Johannesburg", "ZA", -26.139, 28.246)),
          Map.entry("CAI", new Place("CAI", "Cairo", "EG", 30.122, 31.406)),
          Map.entry("NBO", new Place("NBO", "Nairobi", "KE", -1.319, 36.928)),
          Map.entry("DEL", new Place("DEL", "Delhi", "IN", 28.556, 77.100)),
          Map.entry("BOM", new Place("BOM", "Mumbai", "IN", 19.089, 72.868)),
          Map.entry("BLR", new Place("BLR", "Bengaluru", "IN", 13.199, 77.706)),
          Map.entry("SIN", new Place("SIN", "Singapore", "SG", 1.364, 103.991)),
          Map.entry("KUL", new Place("KUL", "Kuala Lumpur", "MY", 2.746, 101.710)),
          Map.entry("BKK", new Place("BKK", "Bangkok", "TH", 13.690, 100.750)),
          Map.entry("HKG", new Place("HKG", "Hong Kong", "HK", 22.308, 113.918)),
          Map.entry("PVG", new Place("PVG", "Shanghai", "CN", 31.143, 121.805)),
          Map.entry("PEK", new Place("PEK", "Beijing", "CN", 40.080, 116.585)),
          Map.entry("TPE", new Place("TPE", "Taipei", "TW", 25.078, 121.233)),
          Map.entry("ICN", new Place("ICN", "Seoul", "KR", 37.460, 126.441)),
          Map.entry("NRT", new Place("NRT", "Tokyo", "JP", 35.772, 140.393)),
          Map.entry("HND", new Place("HND", "Tokyo", "JP", 35.549, 139.780)),
          Map.entry("KIX", new Place("KIX", "Osaka", "JP", 34.434, 135.244)),
          Map.entry("SYD", new Place("SYD", "Sydney", "AU", -33.946, 151.177)),
          Map.entry("MEL", new Place("MEL", "Melbourne", "AU", -37.673, 144.843)),
          Map.entry("BNE", new Place("BNE", "Brisbane", "AU", -27.384, 153.117)),
          Map.entry("PER", new Place("PER", "Perth", "AU", -31.940, 115.967)),
          Map.entry("AKL", new Place("AKL", "Auckland", "NZ", -37.008, 174.792)),
          Map.entry("GRU", new Place("GRU", "Sao Paulo", "BR", -23.432, -46.469)),
          Map.entry("EZE", new Place("EZE", "Buenos Aires", "AR", -34.822, -58.536)),
          Map.entry("SCL", new Place("SCL", "Santiago", "CL", -33.393, -70.786)),
          Map.entry("BOG", new Place("BOG", "Bogota", "CO", 4.701, -74.147)),
          Map.entry("LIM", new Place("LIM", "Lima", "PE", -12.022, -77.114)));

  public static Optional<Place> place(String iata) {
    return iata == null ? Optional.empty() : Optional.ofNullable(PLACES.get(iata.toUpperCase()));
  }

  /** Every catalogued airport with a known position, for suppliers and the frontend. */
  public static List<Place> places() {
    return PLACES.values().stream().sorted(java.util.Comparator.comparing(Place::iata)).toList();
  }

  /** Great-circle distance in kilometres between two catalogued airports; empty when unknown. */
  public static Optional<Double> distanceKm(String from, String to) {
    Optional<Place> a = place(from);
    Optional<Place> b = place(to);
    if (a.isEmpty() || b.isEmpty()) {
      return Optional.empty();
    }
    double lat1 = Math.toRadians(a.get().latitude());
    double lat2 = Math.toRadians(b.get().latitude());
    double dLat = lat2 - lat1;
    double dLon = Math.toRadians(b.get().longitude() - a.get().longitude());
    double h =
        Math.sin(dLat / 2) * Math.sin(dLat / 2)
            + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
    return Optional.of(2 * 6371.0 * Math.asin(Math.sqrt(h)));
  }

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
