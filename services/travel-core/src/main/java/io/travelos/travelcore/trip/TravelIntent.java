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
    int travelers) {

  private static final Pattern IATA = Pattern.compile("^[A-Z]{3}$");

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
