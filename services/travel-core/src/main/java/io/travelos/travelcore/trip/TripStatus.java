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
  CANCELLED,
  FAILED;

  private static final Map<TripStatus, Set<TripStatus>> TRANSITIONS =
      Map.of(
          DRAFT, EnumSet.of(SUBMITTED, CANCELLED),
          SUBMITTED, EnumSet.of(PLANNING, CANCELLED, FAILED),
          PLANNING, EnumSet.of(AWAITING_APPROVAL, APPROVED, CANCELLED, FAILED),
          AWAITING_APPROVAL, EnumSet.of(APPROVED, CANCELLED, FAILED),
          APPROVED, EnumSet.of(BOOKING, CANCELLED),
          BOOKING, EnumSet.of(BOOKED, FAILED),
          BOOKED, EnumSet.of(CANCELLED),
          CANCELLED, EnumSet.noneOf(TripStatus.class),
          FAILED, EnumSet.noneOf(TripStatus.class));

  public boolean canTransitionTo(TripStatus next) {
    return TRANSITIONS.get(this).contains(next);
  }

  public boolean isTerminal() {
    return TRANSITIONS.get(this).isEmpty();
  }
}
