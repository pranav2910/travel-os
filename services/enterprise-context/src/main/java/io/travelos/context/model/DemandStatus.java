package io.travelos.context.model;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The lifecycle of a travel-demand candidate. Detection never books anything; a person (or an
 * explicit delegation rule) converts ACTIONABLE demand into a trip request, which then runs the
 * governed trip lifecycle unchanged.
 */
public enum DemandStatus {
  /** Something is missing or ambiguous: a destination, a date, a possible duplicate. */
  NEEDS_REVIEW,
  /** Complete and eligible: one action away from a trip request. */
  ACTIONABLE,
  /** A person decided no trip is needed. Terminal. */
  DISMISSED,
  /** The source withdrew the commitment (cancelled, deleted, declined, traveler inactive). */
  WITHDRAWN,
  /** A trip request exists; the trip lifecycle owns it from here. Terminal. */
  CONVERTED;

  private static final Map<DemandStatus, Set<DemandStatus>> NEXT =
      Map.of(
          NEEDS_REVIEW, EnumSet.of(ACTIONABLE, DISMISSED, WITHDRAWN, NEEDS_REVIEW),
          ACTIONABLE, EnumSet.of(NEEDS_REVIEW, DISMISSED, WITHDRAWN, CONVERTED, ACTIONABLE),
          WITHDRAWN, EnumSet.of(NEEDS_REVIEW, ACTIONABLE),
          DISMISSED, EnumSet.noneOf(DemandStatus.class),
          CONVERTED, EnumSet.noneOf(DemandStatus.class));

  public boolean canTransitionTo(DemandStatus next) {
    return NEXT.get(this).contains(next);
  }

  public boolean open() {
    return this == NEEDS_REVIEW || this == ACTIONABLE;
  }
}
