package io.travelos.policy.engine;

import io.travelos.common.money.Money;
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
      String routeNote) {}

  /**
   * One bookable plan.
   *
   * @param total everything in the bundle
   * @param air the flight component, if any
   * @param hotel the hotel component, if any
   */
  public record Candidate(String bundleId, Money total, @Nullable Air air, @Nullable Hotel hotel) {}

  /**
   * @param fare the air fare alone (what the lowest-logical-fare band applies to)
   * @param highestCabin the most expensive cabin on any segment
   * @param maxStops stops on the journey with the most stops
   */
  public record Air(Money fare, Cabin highestCabin, int maxStops) {}

  public record Hotel(Money nightlyRate, int nights) {}

  /**
   * Something a principal wants to do to an existing trip.
   *
   * @param action "order.create" | "order.change" | "order.cancel"
   * @param incrementalCost cost added to the trip by the action, when known
   * @param proposed the bundle the action would book, when it books one
   */
  public record Action(
      String action, @Nullable Money incrementalCost, @Nullable Candidate proposed) {}
}
