package io.travelos.travelcore.trip;

import io.travelos.common.geo.Locations;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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

  /**
   * A legacy request that asked for a hotel but did not say which nights. Actionable: the message
   * says what to send instead. Raised before any planning, never after a booking started.
   */
  public static final class HotelRequestException extends IntentRejectedException {
    public static final String CODE = "HOTEL_DETAILS_INSUFFICIENT";

    public HotelRequestException(String message) {
      super(CODE, message);
    }
  }

  /** The widest outbound or return window whose nights are still unambiguous. */
  static final Duration UNAMBIGUOUS_WINDOW = Duration.ofHours(36);

  /**
   * Slice 3 carry-over: {@code hotelRequired=true} on a Slice 1/2 request is honoured or refused,
   * never silently dropped. The request becomes an itinerary with one required stay when the nights
   * follow unambiguously from the windows: check-in on the arrival deadline's local date at the
   * destination, check-out on the return window's local date, each window at most 36 hours wide.
   * Anything else (no return, a same-day return, a multi-day window, an unknown destination clock)
   * is a {@link HotelRequestException} that tells the caller to send an explicit itinerary stay.
   * Requests without {@code hotelRequired} and requests that already carry an itinerary are
   * returned unchanged, so nothing booked before this rule existed is affected.
   */
  public TravelIntent withExplicitStay() {
    if (itinerary != null || !hotelRequired) {
      return this;
    }
    if (returnAfter == null || latestReturn == null) {
      throw new HotelRequestException(
          "hotelRequired needs a return window (returnAfter and latestReturn) so the nights are"
              + " known; or send intent.itinerary with an explicit stay");
    }
    Optional<ZoneId> zone = Locations.zoneOf(destination);
    if (zone.isEmpty()) {
      // not a hotel problem: the place itself is outside the catalog
      throw new IntentRejectedException("UNKNOWN_LOCATION", Locations.explainUnknown(destination));
    }
    if (Duration.between(earliestDeparture, arrivalDeadline).compareTo(UNAMBIGUOUS_WINDOW) > 0) {
      throw new HotelRequestException(
          "the outbound window spans more than 36 hours, so the first night is ambiguous; send"
              + " intent.itinerary with an explicit stay");
    }
    if (Duration.between(returnAfter, latestReturn).compareTo(UNAMBIGUOUS_WINDOW) > 0) {
      throw new HotelRequestException(
          "the return window spans more than 36 hours, so the last night is ambiguous; send"
              + " intent.itinerary with an explicit stay");
    }
    LocalDate checkIn = Locations.localDate(arrivalDeadline, zone.get());
    LocalDate checkOut = Locations.localDate(returnAfter, zone.get());
    if (!checkOut.isAfter(checkIn)) {
      throw new HotelRequestException(
          "hotelRequired with a return on the arrival date ("
              + checkIn
              + " in "
              + destination
              + ") has no night to book; drop hotelRequired or send an explicit stay");
    }
    Itinerary derived =
        Itinerary.of(
            List.of(
                new Itinerary.LegSpec(
                    null, origin, destination, earliestDeparture, arrivalDeadline),
                new Itinerary.LegSpec(null, destination, origin, returnAfter, latestReturn)),
            List.of(new Itinerary.StaySpec(null, destination, checkIn, checkOut, true)),
            List.of(),
            null);
    return TravelIntent.of(derived, purpose, travelers);
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
