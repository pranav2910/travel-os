package io.travelos.travelcore.trip;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * What the traveler needs, frozen. Search, policy and optimization reason over this, never over the
 * free text it came from. Invariants are enforced here so a malformed intent cannot exist.
 */
public record TravelIntent(
    String origin,
    String destination,
    Instant earliestDeparture,
    Instant arrivalDeadline,
    @Nullable Instant returnAfter,
    @Nullable Instant latestReturn,
    @Nullable String purpose,
    boolean hotelRequired,
    int travelers,
    @Nullable Itinerary itinerary) {

  private static final Pattern IATA = Pattern.compile("^[A-Z]{3}$");

  /** The Slice 1/2 shape: one origin, one destination, an optional return. */
  public TravelIntent(
      String origin,
      String destination,
      Instant earliestDeparture,
      Instant arrivalDeadline,
      @Nullable Instant returnAfter,
      @Nullable Instant latestReturn,
      @Nullable String purpose,
      boolean hotelRequired,
      int travelers) {
    this(
        origin,
        destination,
        earliestDeparture,
        arrivalDeadline,
        returnAfter,
        latestReturn,
        purpose,
        hotelRequired,
        travelers,
        null);
  }

  /**
   * Slice 3: an itinerary. The legacy fields describe its first leg (and the last leg as the
   * "return" when the trip comes home) so every Slice 1/2 reader keeps working.
   */
  public static TravelIntent of(Itinerary itinerary, @Nullable String purpose, int travelers) {
    Itinerary.Leg first = itinerary.legs().getFirst();
    Itinerary.Leg last = itinerary.legs().getLast();
    boolean home = itinerary.returnsHome();
    return new TravelIntent(
        first.origin(),
        first.destination(),
        first.earliestDeparture(),
        first.arrivalDeadline(),
        home ? last.earliestDeparture() : null,
        home ? last.arrivalDeadline() : null,
        purpose,
        !itinerary.stays().isEmpty(),
        travelers,
        itinerary);
  }

  /** The stated request, id-free: the input to the idempotency fingerprint. */
  public String canonical() {
    return String.join(
        "|",
        origin,
        destination,
        String.valueOf(earliestDeparture),
        String.valueOf(arrivalDeadline),
        String.valueOf(returnAfter),
        String.valueOf(latestReturn),
        purpose == null ? "" : purpose,
        String.valueOf(hotelRequired),
        String.valueOf(travelers),
        itinerary == null ? "" : itinerary.canonical());
  }

  public boolean isItinerary() {
    return itinerary != null;
  }

  public TravelIntent {
    require(
        IATA.matcher(Objects.requireNonNull(origin, "origin")).matches(),
        "origin must be an IATA code");
    require(
        IATA.matcher(Objects.requireNonNull(destination, "destination")).matches(),
        "destination must be an IATA code");
    require(!origin.equals(destination), "origin and destination must differ");
    Objects.requireNonNull(earliestDeparture, "earliestDeparture");
    Objects.requireNonNull(arrivalDeadline, "arrivalDeadline");
    require(
        earliestDeparture.isBefore(arrivalDeadline),
        "earliestDeparture must be before arrivalDeadline");
    require(
        (returnAfter == null) == (latestReturn == null),
        "returnAfter and latestReturn go together");
    if (returnAfter != null) {
      require(
          !returnAfter.isBefore(arrivalDeadline), "returnAfter must not be before arrivalDeadline");
      require(!latestReturn.isBefore(returnAfter), "latestReturn must not be before returnAfter");
    }
    require(travelers >= 1 && travelers <= 9, "travelers must be 1-9");
  }

  public boolean isRoundTrip() {
    return returnAfter != null;
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new IllegalArgumentException(message);
    }
  }
}
