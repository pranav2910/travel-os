package io.travelos.travelcore.trip;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** The trip lifecycle. Transitions are explicit; anything not listed is a bug, not a feature. */
public enum TripStatus {
  DRAFT,
  SUBMITTED,
  PLANNING,
  AWAITING_APPROVAL,
  APPROVED,
  BOOKING,
  BOOKED,
  COMPLETED,
  CANCELLED,
  FAILED;

  private static final Map<TripStatus, Set<TripStatus>> TRANSITIONS =
      Map.of(
          DRAFT, EnumSet.of(SUBMITTED, CANCELLED),
          SUBMITTED, EnumSet.of(PLANNING, CANCELLED, FAILED),
          PLANNING, EnumSet.of(AWAITING_APPROVAL, APPROVED, CANCELLED, FAILED),
          AWAITING_APPROVAL, EnumSet.of(APPROVED, CANCELLED, FAILED),
          // Slice 3: revalidation before booking found a material change (price up, quote gone):
          // the approved plan is stale and a person decides again.
          APPROVED, EnumSet.of(BOOKING, AWAITING_APPROVAL, CANCELLED),
          BOOKING, EnumSet.of(BOOKED, FAILED),
          // Slice 5: completion is attested by a person (the traveler after the last arrival, or a
          // travel admin), never inferred from a booking.
          BOOKED, EnumSet.of(COMPLETED, CANCELLED),
          COMPLETED, EnumSet.noneOf(TripStatus.class),
          CANCELLED, EnumSet.noneOf(TripStatus.class),
          FAILED, EnumSet.noneOf(TripStatus.class));

  public boolean canTransitionTo(TripStatus next) {
    return TRANSITIONS.get(this).contains(next);
  }

  public boolean isTerminal() {
    return TRANSITIONS.get(this).isEmpty();
  }
}
