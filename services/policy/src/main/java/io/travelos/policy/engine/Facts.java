package io.travelos.policy.engine;

import io.travelos.common.money.Money;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The engine's input vocabulary. Plain values, no protobuf, so rules are trivially unit-testable.
 */
public final class Facts {

  private Facts() {}

  /**
   * @param international true when the itinerary crosses a border or an airport is unknown
   * @param routeNote how the classification was reached, for the audit trail
   */
  public record Trip(
      String tripId,
      String travelerId,
      String origin,
      String destination,
      boolean international,
      String routeNote,
      @Nullable Instant referenceTime,
      @Nullable Instant earliestDeparture,
      @Nullable Instant latestReturn) {
    /** Without the times the booking-horizon rules have nothing to say. */
    public Trip(
        String tripId,
        String travelerId,
        String origin,
        String destination,
        boolean international,
        String routeNote) {
      this(tripId, travelerId, origin, destination, international, routeNote, null, null, null);
    }
  }

  /**
   * One bookable plan.
   *
   * @param total everything in the bundle
   * @param air the flight component, if any
   * @param hotel the hotel component, if any
   */
  public record Candidate(
      String bundleId,
      Money total,
      @Nullable Air air,
      @Nullable Hotel hotel,
      List<Hotel> hotels,
      List<Ground> ground) {
    public Candidate {
      hotels = List.copyOf(hotels);
      ground = List.copyOf(ground);
    }

    /** The Slice 1/2 shape: one air component, at most one hotel. */
    public Candidate(String bundleId, Money total, @Nullable Air air, @Nullable Hotel hotel) {
      this(bundleId, total, air, hotel, hotel == null ? List.of() : List.of(hotel), List.of());
    }
  }

  /** One ground transfer (Slice 3). */
  public record Ground(Money fare, String vehicleClass, @Nullable String componentId) {}

  /** When one leg of a proposed multi-leg itinerary flies (Slice 3), keyed by its component id. */
  public record LegTiming(String componentId, Instant departure, Instant arrival) {}

  /** The window a leg must fly in (Slice 3), from the frozen itinerary. */
  public record Window(Instant earliestDeparture, Instant arrivalDeadline) {}

  /**
   * @param fare the air fare alone (what the lowest-logical-fare band applies to)
   * @param highestCabin the most expensive cabin on any segment
   * @param maxStops stops on the journey with the most stops
   */
  public record Air(Money fare, Cabin highestCabin, int maxStops) {}

  public record Hotel(Money nightlyRate, int nights, @Nullable String componentId) {
    public Hotel(Money nightlyRate, int nights) {
      this(nightlyRate, nights, null);
    }
  }

  /**
   * The time constraints a trip was planned for (from its frozen intent). A replacement itinerary
   * must satisfy them; policy checks this itself rather than trusting whoever proposes the change.
   */
  public record Constraints(
      @Nullable Instant earliestDeparture,
      @Nullable Instant arrivalDeadline,
      @Nullable Instant returnAfter,
      @Nullable Instant latestReturn,
      Map<String, Window> legWindows) {
    public Constraints {
      legWindows = Map.copyOf(legWindows);
    }

    public Constraints(
        @Nullable Instant earliestDeparture,
        @Nullable Instant arrivalDeadline,
        @Nullable Instant returnAfter,
        @Nullable Instant latestReturn) {
      this(earliestDeparture, arrivalDeadline, returnAfter, latestReturn, Map.of());
    }
  }

  /** When a proposed itinerary flies: first departure, final arrival, and the return leg if any. */
  public record Itinerary(
      Instant outboundDeparture,
      Instant outboundArrival,
      @Nullable Instant inboundDeparture,
      @Nullable Instant inboundArrival) {}

  /**
   * Something a principal wants to do to an existing trip.
   *
   * @param action canonical capability: "order.create" | "order.change" | "order.cancel". The
   *     aliases CREATE_ORDER, CHANGE_EXISTING_ORDER and CANCEL_ORDER are accepted and
   *     canonicalized.
   * @param incrementalCost cost added to the trip by the action, when known
   * @param proposed the bundle the action would book, when it books one
   * @param constraints the trip's time constraints, when the caller wants them enforced
   * @param itinerary when the proposed bundle flies, when known
   */
  public record Action(
      String action,
      @Nullable Money incrementalCost,
      @Nullable Candidate proposed,
      @Nullable Constraints constraints,
      @Nullable Itinerary itinerary,
      List<LegTiming> legTimings) {
    public Action(
        String action,
        @Nullable Money incrementalCost,
        @Nullable Candidate proposed,
        @Nullable Constraints constraints,
        @Nullable Itinerary itinerary) {
      this(action, incrementalCost, proposed, constraints, itinerary, List.of());
    }

    public static final String ORDER_CREATE = "order.create";
    public static final String ORDER_CHANGE = "order.change";
    public static final String ORDER_CANCEL = "order.cancel";

    public Action {
      action = canonical(action);
      legTimings = List.copyOf(legTimings);
    }

    public Action(String action, @Nullable Money incrementalCost, @Nullable Candidate proposed) {
      this(action, incrementalCost, proposed, null, null);
    }

    /** {@code CHANGE_EXISTING_ORDER} and friends name the same capability as the dotted form. */
    public static String canonical(String action) {
      return switch (action == null ? "" : action.trim()) {
        case "CREATE_ORDER", "ORDER_CREATE" -> ORDER_CREATE;
        case "CHANGE_EXISTING_ORDER", "CHANGE_ORDER", "ORDER_CHANGE" -> ORDER_CHANGE;
        case "CANCEL_ORDER", "ORDER_CANCEL" -> ORDER_CANCEL;
        default -> action == null ? "" : action.trim();
      };
    }
  }
}
