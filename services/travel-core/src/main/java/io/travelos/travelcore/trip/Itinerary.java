package io.travelos.travelcore.trip;

import io.travelos.common.geo.Locations;
import io.travelos.common.ids.IdPrefix;
import io.travelos.common.ids.Ids;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The ordered components of a multi-city trip (Slice 3): legs, stays and transfers with stable ids
 * and explicit dependencies. Frozen with the intent; search, policy, optimization and booking all
 * reason over this. Invariants live here so a malformed itinerary cannot exist:
 *
 * <ul>
 *   <li>legs chain (each leg departs where the previous one landed) and run forward in time
 *   <li>every stay is in a city a leg lands in, on the property's local calendar, at least one
 *       night, between the leg that arrives and the leg that departs
 *   <li>airport transfers hang off the leg they meet; point-to-point transfers carry their pickup
 *   <li>every location is one the platform knows the clock of; one currency for the whole trip
 * </ul>
 */
public record Itinerary(
    List<Leg> legs, List<Stay> stays, List<Transfer> transfers, String currency) {

  private static final Pattern IATA = Pattern.compile("^[A-Z]{3}$");
  static final Set<String> TRANSFER_KINDS =
      Set.of("AIRPORT_TO_HOTEL", "HOTEL_TO_AIRPORT", "POINT_TO_POINT");

  public record Leg(
      String componentId,
      int sequence,
      String origin,
      String destination,
      Instant earliestDeparture,
      Instant arrivalDeadline,
      ZoneId originZone,
      ZoneId destinationZone,
      List<String> dependsOn) {
    public Leg {
      dependsOn = List.copyOf(dependsOn);
    }
  }

  public record Stay(
      String componentId,
      String city,
      LocalDate checkIn,
      LocalDate checkOut,
      ZoneId zone,
      boolean required,
      List<String> dependsOn) {
    public Stay {
      dependsOn = List.copyOf(dependsOn);
    }

    public int nights() {
      return Locations.nights(checkIn, checkOut);
    }

    /** The leg whose landing this stay follows (first dependency), if any. */
    public Optional<String> arrivalLeg() {
      return dependsOn.isEmpty() ? Optional.empty() : Optional.of(dependsOn.getFirst());
    }

    public Optional<String> departureLeg() {
      return dependsOn.size() < 2 ? Optional.empty() : Optional.of(dependsOn.get(1));
    }
  }

  public record Transfer(
      String componentId,
      String kind,
      String city,
      String from,
      String to,
      @Nullable Instant pickup,
      ZoneId zone,
      boolean required,
      List<String> dependsOn) {
    public Transfer {
      dependsOn = List.copyOf(dependsOn);
    }

    /**
     * The leg this transfer meets (arriving for AIRPORT_TO_HOTEL, departing for HOTEL_TO_AIRPORT).
     */
    public Optional<String> leg() {
      return dependsOn.isEmpty() ? Optional.empty() : Optional.of(dependsOn.getFirst());
    }
  }

  public Itinerary {
    legs = List.copyOf(Objects.requireNonNull(legs, "legs"));
    stays = List.copyOf(Objects.requireNonNull(stays, "stays"));
    transfers = List.copyOf(Objects.requireNonNull(transfers, "transfers"));
    require(!legs.isEmpty(), "an itinerary needs at least one leg");
    require(legs.size() <= 8, "at most 8 legs");
    currency = currency == null || currency.isBlank() ? "USD" : currency.trim().toUpperCase();
    try {
      Currency.getInstance(currency);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("currency must be an ISO 4217 code: " + currency);
    }
    Set<String> ids = new HashSet<>();
    for (Leg l : legs) {
      require(ids.add(l.componentId()), "duplicate component id " + l.componentId());
      require(IATA.matcher(l.origin()).matches(), "leg origin must be an IATA code");
      require(IATA.matcher(l.destination()).matches(), "leg destination must be an IATA code");
      require(!l.origin().equals(l.destination()), "a leg must go somewhere");
      require(
          l.earliestDeparture().isBefore(l.arrivalDeadline()),
          "leg "
              + l.origin()
              + "-"
              + l.destination()
              + ": earliestDeparture must precede arrivalDeadline");
    }
    for (int i = 1; i < legs.size(); i++) {
      Leg prev = legs.get(i - 1);
      Leg leg = legs.get(i);
      require(
          leg.origin().equals(prev.destination()),
          "leg "
              + (i + 1)
              + " must depart from "
              + prev.destination()
              + ", where leg "
              + i
              + " lands");
      require(
          !leg.earliestDeparture().isBefore(prev.earliestDeparture())
              && leg.arrivalDeadline().isAfter(prev.arrivalDeadline()),
          "leg " + (i + 1) + " must come after leg " + i);
    }
    for (Stay s : stays) {
      require(ids.add(s.componentId()), "duplicate component id " + s.componentId());
      require(IATA.matcher(s.city()).matches(), "stay city must be an IATA code");
      require(
          s.checkOut().isAfter(s.checkIn()),
          "stay in " + s.city() + ": checkOut must follow checkIn");
      require(s.nights() <= 30, "a stay is at most 30 nights");
      require(
          legs.stream().anyMatch(l -> l.destination().equals(s.city())),
          "stay in " + s.city() + ": no leg lands there");
      for (String dep : s.dependsOn()) {
        require(
            legs.stream().anyMatch(l -> l.componentId().equals(dep)),
            "stay in " + s.city() + " depends on unknown component " + dep);
      }
    }
    for (Transfer t : transfers) {
      require(ids.add(t.componentId()), "duplicate component id " + t.componentId());
      require(TRANSFER_KINDS.contains(t.kind()), "transfer kind must be one of " + TRANSFER_KINDS);
      require(IATA.matcher(t.city()).matches(), "transfer city must be an IATA code");
      if ("POINT_TO_POINT".equals(t.kind())) {
        require(t.pickup() != null, "a point-to-point transfer needs a pickup time");
      } else {
        require(!t.dependsOn().isEmpty(), "an airport transfer must hang off a leg");
        for (String dep : t.dependsOn()) {
          require(
              legs.stream().anyMatch(l -> l.componentId().equals(dep)),
              "transfer in " + t.city() + " depends on unknown component " + dep);
        }
      }
    }
  }

  /**
   * The request as the traveler stated it, without the ids and dependencies the platform derived:
   * two submissions of the same itinerary must fingerprint the same (idempotent retries) even
   * though each parse mints fresh component ids.
   */
  public String canonical() {
    StringBuilder b = new StringBuilder(currency);
    for (Leg l : legs) {
      b.append("|L:")
          .append(l.origin())
          .append('>')
          .append(l.destination())
          .append('@')
          .append(l.earliestDeparture())
          .append("..")
          .append(l.arrivalDeadline());
    }
    for (Stay s : stays) {
      b.append("|S:")
          .append(s.city())
          .append('@')
          .append(s.checkIn())
          .append("..")
          .append(s.checkOut())
          .append(s.required() ? "" : "?");
    }
    for (Transfer t : transfers) {
      b.append("|T:")
          .append(t.kind())
          .append(':')
          .append(t.city())
          .append(':')
          .append(t.from())
          .append('>')
          .append(t.to())
          .append('@')
          .append(t.pickup() == null ? "" : t.pickup())
          .append(t.required() ? "" : "?");
    }
    return b.toString();
  }

  /** Every component id, in itinerary order: legs, then stays, then transfers. */
  public List<String> componentIds() {
    List<String> out = new ArrayList<>();
    legs.forEach(l -> out.add(l.componentId()));
    stays.forEach(s -> out.add(s.componentId()));
    transfers.forEach(t -> out.add(t.componentId()));
    return out;
  }

  public Optional<Leg> leg(String componentId) {
    return legs.stream().filter(l -> l.componentId().equals(componentId)).findFirst();
  }

  public Instant firstDeparture() {
    return legs.getFirst().earliestDeparture();
  }

  public Instant lastArrival() {
    return legs.getLast().arrivalDeadline();
  }

  /** True when the last leg lands where the first one departed: the legacy "round trip". */
  public boolean returnsHome() {
    return legs.size() > 1 && legs.getLast().destination().equals(legs.getFirst().origin());
  }

  // ------------------------------------------------------------------ construction helpers

  /**
   * Builds an itinerary from what a client can reasonably say: legs with times, stays with local
   * dates, transfers with a kind. Zones come from the platform's location table (unknown places are
   * rejected, never assumed UTC); dependencies are derived from the chaining; missing component ids
   * are minted.
   */
  public static Itinerary of(
      List<LegSpec> legSpecs,
      List<StaySpec> staySpecs,
      List<TransferSpec> transferSpecs,
      @Nullable String currency) {
    List<Leg> legs = new ArrayList<>();
    int seq = 1;
    for (LegSpec spec : legSpecs) {
      String id = idOrNew(spec.componentId());
      List<String> deps = legs.isEmpty() ? List.of() : List.of(legs.getLast().componentId());
      legs.add(
          new Leg(
              id,
              seq++,
              spec.origin(),
              spec.destination(),
              spec.earliestDeparture(),
              spec.arrivalDeadline(),
              zone(spec.origin()),
              zone(spec.destination()),
              deps));
    }
    List<Stay> stays = new ArrayList<>();
    for (StaySpec spec : staySpecs) {
      List<String> deps = new ArrayList<>();
      arrivingLeg(legs, spec.city(), spec.checkIn()).ifPresent(deps::add);
      departingLeg(legs, spec.city(), spec.checkOut()).ifPresent(deps::add);
      stays.add(
          new Stay(
              idOrNew(spec.componentId()),
              spec.city(),
              spec.checkIn(),
              spec.checkOut(),
              zone(spec.city()),
              spec.required(),
              deps));
    }
    List<Transfer> transfers = new ArrayList<>();
    for (TransferSpec spec : transferSpecs) {
      List<String> deps = new ArrayList<>();
      if ("AIRPORT_TO_HOTEL".equals(spec.kind())) {
        legs.stream()
            .filter(l -> l.destination().equals(spec.city()))
            .findFirst()
            .ifPresent(l -> deps.add(l.componentId()));
      } else if ("HOTEL_TO_AIRPORT".equals(spec.kind())) {
        legs.stream()
            .filter(l -> l.origin().equals(spec.city()))
            .reduce((a, b) -> b)
            .ifPresent(l -> deps.add(l.componentId()));
      }
      transfers.add(
          new Transfer(
              idOrNew(spec.componentId()),
              spec.kind(),
              spec.city(),
              spec.from() == null || spec.from().isBlank() ? spec.city() + " airport" : spec.from(),
              spec.to() == null || spec.to().isBlank() ? "hotel" : spec.to(),
              spec.pickup(),
              zone(spec.city()),
              spec.required(),
              deps));
    }
    return new Itinerary(legs, stays, transfers, currency);
  }

  public record LegSpec(
      @Nullable String componentId,
      String origin,
      String destination,
      Instant earliestDeparture,
      Instant arrivalDeadline) {}

  public record StaySpec(
      @Nullable String componentId,
      String city,
      LocalDate checkIn,
      LocalDate checkOut,
      boolean required) {}

  public record TransferSpec(
      @Nullable String componentId,
      String kind,
      String city,
      @Nullable String from,
      @Nullable String to,
      @Nullable Instant pickup,
      boolean required) {}

  /** The last leg landing in the city whose deadline's local date is on or before the check-in. */
  private static Optional<String> arrivingLeg(List<Leg> legs, String city, LocalDate checkIn) {
    String found = null;
    for (Leg l : legs) {
      if (l.destination().equals(city)
          && !Locations.localDate(l.arrivalDeadline(), l.destinationZone()).isAfter(checkIn)) {
        found = l.componentId();
      }
    }
    if (found == null) {
      for (Leg l : legs) {
        if (l.destination().equals(city)) {
          return Optional.of(l.componentId());
        }
      }
    }
    return Optional.ofNullable(found);
  }

  /** The first leg leaving the city on or after the check-out date. */
  private static Optional<String> departingLeg(List<Leg> legs, String city, LocalDate checkOut) {
    for (Leg l : legs) {
      if (l.origin().equals(city)
          && !Locations.localDate(l.earliestDeparture(), l.originZone()).isBefore(checkOut)) {
        return Optional.of(l.componentId());
      }
    }
    return Optional.empty();
  }

  private static ZoneId zone(String iata) {
    return Locations.zoneOf(iata)
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    "unknown location " + iata + ": the platform does not know its clock"));
  }

  private static String idOrNew(@Nullable String id) {
    if (id == null || id.isBlank()) {
      return Ids.newId(IdPrefix.COMPONENT);
    }
    require(id.startsWith("cmp_") && id.length() == 30, "component id must be cmp_<ulid>: " + id);
    return id;
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new IllegalArgumentException(message);
    }
  }
}
