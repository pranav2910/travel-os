package io.travelos.travelcore.trip;

import io.travelos.common.geo.Locations;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * What a new request must satisfy before the platform accepts it, checked at creation (and never
 * when a stored trip is read back, so trips accepted under older rules still load):
 *
 * <ul>
 *   <li>every location, on every path, is an airport in the platform's catalog; a city code names
 *       the airports it stands for ({@code UNKNOWN_LOCATION})
 *   <li>no leg's outbound window has already closed and no stay has already ended, judged against
 *       the platform clock and reported in the departure location's local time ({@code
 *       DEPARTURE_IN_PAST}, {@code STAY_IN_PAST})
 *   <li>the trip is priced in a currency every component can be quoted in (USD in the sandbox);
 *       nothing converts ({@code CURRENCY_UNSUPPORTED})
 * </ul>
 *
 * <p>A window that is still open is accepted even when it began in the past ("leave later today");
 * the workflow checks the chosen departure again right before booking.
 */
final class IntentValidation {

  /**
   * The currencies a whole trip can be priced in: every trip flies, and the sandbox airline quotes
   * USD only; the policy engine and the optimizer never convert, so a GBP-priced trip could never
   * compose a flight (a GBP-quoted hotel in a USD trip is refused by policy, as designed).
   */
  static final Set<String> SUPPORTED_CURRENCIES = Set.of("USD");

  private static final DateTimeFormatter LOCAL =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);

  private IntentValidation() {}

  static void check(TravelIntent intent, Instant now) {
    Itinerary itinerary = intent.itinerary();
    if (itinerary == null) {
      requireKnown(List.of(intent.origin(), intent.destination()));
      requireOpen("outbound", intent.origin(), intent.arrivalDeadline(), now);
      if (intent.latestReturn() != null) {
        requireOpen("return", intent.destination(), intent.latestReturn(), now);
      }
      return;
    }
    List<String> codes = new ArrayList<>();
    for (Itinerary.Leg l : itinerary.legs()) {
      codes.add(l.origin());
      codes.add(l.destination());
    }
    itinerary.stays().forEach(s -> codes.add(s.city()));
    itinerary.transfers().forEach(t -> codes.add(t.city()));
    requireKnown(codes);
    if (!SUPPORTED_CURRENCIES.contains(itinerary.currency())) {
      throw new IntentRejectedException(
          "CURRENCY_UNSUPPORTED",
          "currency "
              + itinerary.currency()
              + " is not supported: trips are priced in "
              + String.join(", ", new TreeSet<>(SUPPORTED_CURRENCIES))
              + " (the platform does not convert currencies)");
    }
    for (Itinerary.Leg l : itinerary.legs()) {
      requireOpen(
          "leg " + l.origin() + "-" + l.destination(), l.origin(), l.arrivalDeadline(), now);
    }
    for (Itinerary.Stay s : itinerary.stays()) {
      Instant checkOut = Locations.checkOut(s.checkOut(), s.zone());
      if (!checkOut.isAfter(now)) {
        throw new IntentRejectedException(
            "STAY_IN_PAST",
            "the stay in "
                + s.city()
                + " ends "
                + s.checkOut()
                + " (check-out "
                + local(checkOut, s.zone())
                + "), which has already passed; it is now "
                + local(now, s.zone()));
      }
    }
  }

  private static void requireKnown(List<String> codes) {
    List<String> unknown = Locations.unknown(codes);
    if (!unknown.isEmpty()) {
      throw new IntentRejectedException(
          "UNKNOWN_LOCATION",
          String.join("; ", unknown.stream().map(Locations::explainUnknown).toList()));
    }
  }

  /** The window closes at its deadline: a deadline at or before now cannot be flown any more. */
  private static void requireOpen(String what, String departure, Instant deadline, Instant now) {
    if (deadline.isAfter(now)) {
      return;
    }
    ZoneId zone = Locations.zoneOrThrow(departure);
    throw new IntentRejectedException(
        "DEPARTURE_IN_PAST",
        "the "
            + what
            + " window closed at "
            + local(deadline, zone)
            + " ("
            + deadline
            + "); it is now "
            + local(now, zone)
            + " at "
            + departure
            + ": choose a departure in the future");
  }

  private static String local(Instant at, ZoneId zone) {
    return LOCAL.format(at.atZone(zone)) + " " + zone.getId();
  }
}
