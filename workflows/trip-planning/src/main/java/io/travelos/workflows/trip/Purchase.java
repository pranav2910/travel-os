package io.travelos.workflows.trip;

import com.google.protobuf.Timestamp;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.travelos.contracts.common.v1.Cabin;
import io.travelos.contracts.common.v1.Money;
import io.travelos.contracts.offer.v1.Bundle;
import io.travelos.contracts.offer.v1.FlightSegment;
import io.travelos.contracts.offer.v1.Journey;
import io.travelos.contracts.offer.v1.Offer;
import io.travelos.contracts.optimization.v1.RankedCandidate;
import io.travelos.contracts.policy.v1.PolicyDecision;
import io.travelos.contracts.trip.v1.SearchPreferences;
import io.travelos.contracts.trip.v1.TravelIntent;
import io.travelos.contracts.trip.v1.Trip;
import io.travelos.contracts.trip.v1.TripAlternative;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Phase 3 (ADR-0015): the pure parts of separating planning from purchase. Who must authorize a
 * purchase, what a person is shown to choose between, what a quote binds, and how search
 * preferences narrow what is looked at. No I/O, no Temporal state.
 */
final class Purchase {

  /** How long a quote without supplier expiry stands before a person must refresh it. */
  static final long DEFAULT_QUOTE_LIFETIME_MILLIS = 24L * 60 * 60 * 1000;

  static final int MAX_ALTERNATIVES = 5;

  private static final DateTimeFormatter HHMM =
      DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneOffset.UTC);

  private Purchase() {}

  /**
   * A person confirms whenever the request asked for it, or whenever policy did not grant
   * autonomous purchase authority for this very plan. Policy's grant is the only other way in.
   */
  static boolean confirmRequired(Trip trip, PolicyDecision decision) {
    return "CONFIRM".equals(trip.getPurchaseMode()) || !decision.getAutonomousPurchase();
  }

  /** The move to BOOKING was refused for lack of a covering purchase authorization. */
  static boolean notAuthorized(ActivityFailure e) {
    return e.getCause() instanceof ApplicationFailure af
        && "FAILED_PRECONDITION".equals(af.getType())
        && af.getOriginalMessage() != null
        && af.getOriginalMessage().contains("PURCHASE_NOT_AUTHORIZED");
  }

  /** The ranked, permitted plans a person may choose between, the selected one first by rank. */
  static List<TripAlternative> alternatives(
      List<RankedCandidate> ranking,
      List<Bundle> permitted,
      Map<String, PolicyDecision> decisions) {
    List<TripAlternative> out = new ArrayList<>();
    List<RankedCandidate> sorted = new ArrayList<>(ranking);
    sorted.sort((a, b) -> Integer.compare(a.getRank(), b.getRank()));
    for (RankedCandidate r : sorted) {
      if (!r.getFeasible() || out.size() >= MAX_ALTERNATIVES) {
        continue;
      }
      PolicyDecision d = decisions.get(r.getBundleId());
      if (d != null && d.getOutcome() == io.travelos.contracts.policy.v1.Outcome.DENY) {
        continue;
      }
      permitted.stream()
          .filter(b -> b.getBundleId().equals(r.getBundleId()))
          .findFirst()
          .ifPresent(b -> out.add(alternative(b, r.getRank())));
    }
    return out;
  }

  static TripAlternative alternative(Bundle b, int rank) {
    TripAlternative.Builder alt =
        TripAlternative.newBuilder()
            .setBundleId(b.getBundleId())
            .setTotal(b.getTotal())
            .setSummary(describe(b))
            .setRank(rank)
            .setRefundable(b.getOffersList().stream().allMatch(Purchase::refundable))
            .setConditions(conditions(b));
    Money penalty = changePenalty(b);
    if (penalty != null) {
      alt.setChangePenalty(penalty);
    }
    return alt.build();
  }

  static String describe(Bundle b) {
    List<String> parts = new ArrayList<>();
    for (Offer o : b.getOffersList()) {
      parts.add(describe(o));
    }
    return String.join("; ", parts);
  }

  static String describe(Offer o) {
    switch (o.getType()) {
      case AIR -> {
        StringBuilder s = new StringBuilder();
        s.append(journey(o.getAir().getOutbound()));
        if (o.getAir().hasInbound() && o.getAir().getInbound().getSegmentsCount() > 0) {
          s.append(" / ").append(journey(o.getAir().getInbound()));
        }
        return s.toString();
      }
      case HOTEL -> {
        return o.getHotel().getName()
            + ", "
            + o.getHotel().getNights()
            + " night(s) "
            + o.getHotel().getRoomType();
      }
      case GROUND -> {
        return o.getGround().getVendorName()
            + " "
            + o.getGround().getVehicleClass()
            + " "
            + o.getGround().getPickupLocation()
            + " -> "
            + o.getGround().getDropoffLocation();
      }
      default -> {
        return o.getType().name();
      }
    }
  }

  private static String journey(Journey j) {
    if (j.getSegmentsCount() == 0) {
      return "flight";
    }
    FlightSegment first = j.getSegments(0);
    FlightSegment last = j.getSegments(j.getSegmentsCount() - 1);
    int stops = j.getSegmentsCount() - 1;
    return first.getCarrier()
        + (first.getFlightNumber().isBlank() ? "" : " " + first.getFlightNumber())
        + " "
        + first.getOrigin()
        + "-"
        + last.getDestination()
        + " "
        + time(first.getDeparture())
        + "-"
        + time(last.getArrival())
        + (stops == 0 ? " nonstop" : " " + stops + " stop" + (stops == 1 ? "" : "s"))
        + (first.getCabin() == Cabin.CABIN_UNSPECIFIED ? "" : " " + first.getCabin().name());
  }

  private static String time(Timestamp t) {
    return t.getSeconds() == 0 ? "?" : HHMM.format(Instant.ofEpochSecond(t.getSeconds()));
  }

  /** Fare conditions in words: what the authorization binds besides the price. */
  static String conditions(Bundle b) {
    List<String> parts = new ArrayList<>();
    for (Offer o : b.getOffersList()) {
      StringBuilder s = new StringBuilder(o.getType().name().toLowerCase()).append(": ");
      s.append(refundable(o) ? "refundable" : "non-refundable");
      if (o.hasCancellation()
          && o.getCancellation().hasPenalty()
          && o.getCancellation().getPenalty().getAmountMinor() > 0) {
        s.append(", cancellation penalty ").append(money(o.getCancellation().getPenalty()));
      }
      if (o.hasCancellation() && o.getCancellation().hasFreeUntil()) {
        s.append(", free cancellation until ")
            .append(Instant.ofEpochSecond(o.getCancellation().getFreeUntil().getSeconds()));
      }
      if (o.hasChangePenalty() && o.getChangePenalty().getAmountMinor() > 0) {
        s.append(", change fee ").append(money(o.getChangePenalty()));
      }
      if (o.hasExpiresAt() && o.getExpiresAt().getSeconds() > 0) {
        s.append(", quote valid until ")
            .append(Instant.ofEpochSecond(o.getExpiresAt().getSeconds()));
      }
      parts.add(s.toString());
    }
    return String.join("; ", parts);
  }

  static boolean refundable(Offer o) {
    return o.getRefundable() || (o.hasCancellation() && o.getCancellation().getRefundable());
  }

  private static @Nullable Money changePenalty(Bundle b) {
    Money out = null;
    for (Offer o : b.getOffersList()) {
      if (o.hasChangePenalty()) {
        out =
            out == null
                ? o.getChangePenalty()
                : out.toBuilder()
                    .setAmountMinor(out.getAmountMinor() + o.getChangePenalty().getAmountMinor())
                    .build();
      }
    }
    return out;
  }

  /** The earliest supplier expiry among the offers, or a default lifetime from now. */
  static Timestamp quoteExpiry(Bundle b, long nowMillis) {
    long earliest = Long.MAX_VALUE;
    for (Offer o : b.getOffersList()) {
      if (o.hasExpiresAt() && o.getExpiresAt().getSeconds() > 0) {
        earliest = Math.min(earliest, o.getExpiresAt().getSeconds() * 1000);
      }
    }
    long at = earliest == Long.MAX_VALUE ? nowMillis + DEFAULT_QUOTE_LIFETIME_MILLIS : earliest;
    return Timestamp.newBuilder()
        .setSeconds(at / 1000)
        .setNanos((int) (at % 1000) * 1_000_000)
        .build();
  }

  static String money(Money m) {
    return m.getCurrency()
        + " "
        + (m.getAmountMinor() / 100)
        + "."
        + String.format("%02d", m.getAmountMinor() % 100);
  }

  // ------------------------------------------------------------------ search preferences

  /** The cabins to ask suppliers for: the preferred one and everything below it, else all. */
  static List<Cabin> cabins(TravelIntent intent) {
    List<Cabin> all = List.of(Cabin.ECONOMY, Cabin.PREMIUM_ECONOMY, Cabin.BUSINESS);
    if (!intent.hasPreferences() || intent.getPreferences().getCabin().isBlank()) {
      return all;
    }
    Cabin wanted;
    try {
      wanted = Cabin.valueOf(intent.getPreferences().getCabin());
    } catch (IllegalArgumentException e) {
      return all;
    }
    if (wanted == Cabin.FIRST) {
      return List.of(Cabin.ECONOMY, Cabin.PREMIUM_ECONOMY, Cabin.BUSINESS, Cabin.FIRST);
    }
    List<Cabin> out = new ArrayList<>();
    for (Cabin c : all) {
      out.add(c);
      if (c == wanted) {
        break;
      }
    }
    return out;
  }

  /**
   * Offers that satisfy the stated preferences (nonstop, refundable, stops); others are dropped.
   */
  static List<Offer> filter(List<Offer> offers, TravelIntent intent) {
    if (!intent.hasPreferences()) {
      return offers;
    }
    SearchPreferences p = intent.getPreferences();
    List<Offer> out = new ArrayList<>();
    for (Offer o : offers) {
      if (o.getType() == io.travelos.contracts.offer.v1.OfferType.AIR) {
        int stops = stops(o);
        if (p.getNonstopOnly() && stops > 0) {
          continue;
        }
        if (p.getMaxStops() > 0 && stops > p.getMaxStops()) {
          continue;
        }
      }
      if (p.getRefundableOnly() && !refundable(o)) {
        continue;
      }
      out.add(o);
    }
    return out;
  }

  static int stops(Offer o) {
    int stops = 0;
    if (o.getAir().hasOutbound()) {
      stops = Math.max(stops, o.getAir().getOutbound().getSegmentsCount() - 1);
    }
    if (o.getAir().hasInbound()) {
      stops = Math.max(stops, o.getAir().getInbound().getSegmentsCount() - 1);
    }
    return Math.max(0, stops);
  }

  /** Preferred carriers go to the optimizer as a soft preference, never as a filter. */
  static List<String> preferredCarriers(TravelIntent intent) {
    return intent.hasPreferences() ? intent.getPreferences().getPreferredCarriersList() : List.of();
  }
}
