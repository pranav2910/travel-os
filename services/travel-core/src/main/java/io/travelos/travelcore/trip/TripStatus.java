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
  CANCELLING,
  CANCELLED,
  FAILED,
  /**
   * Phase 3: planned and priced; nothing is reserved until a purchase authorization exists. A
   * person confirms (or re-selects, or refreshes the quote) here.
   */
  QUOTED;

  private static final Map<TripStatus, Set<TripStatus>> TRANSITIONS =
      Map.ofEntries(
          Map.entry(DRAFT, EnumSet.of(SUBMITTED, CANCELLED)),
          Map.entry(SUBMITTED, EnumSet.of(PLANNING, CANCELLED, FAILED)),
          Map.entry(PLANNING, EnumSet.of(QUOTED, AWAITING_APPROVAL, APPROVED, CANCELLED, FAILED)),
          // Phase 3: QUOTED -> QUOTED is a re-selection or a refreshed price; -> PLANNING a fresh
          // search after a quote died; a person's authorization moves it on to approval/booking.
          Map.entry(
              QUOTED, EnumSet.of(QUOTED, AWAITING_APPROVAL, APPROVED, PLANNING, CANCELLED, FAILED)),
          Map.entry(AWAITING_APPROVAL, EnumSet.of(APPROVED, CANCELLED, FAILED)),
          // Slice 3: revalidation before booking found a material change (price up): the approved
          // plan is stale and a person decides again. A quote that expired while the trip waited
          // sends it back to PLANNING (a fresh search), and a supplier gone for good after approval
          // is a FAILED trip, recorded as such rather than left looking approved.
          Map.entry(
              APPROVED,
              EnumSet.of(BOOKING, AWAITING_APPROVAL, QUOTED, PLANNING, CANCELLED, FAILED)),
          Map.entry(BOOKING, EnumSet.of(BOOKED, FAILED)),
          // Slice 5: completion is attested by a person (the traveler after the last arrival, or a
          // travel admin), never inferred from a booking. A booked trip is never CANCELLED
          // directly:
          // its reservation must be released at the suppliers first (CANCELLING), and CANCELLED is
          // recorded only once that is established. A refused release keeps the trip CANCELLING
          // with
          // an exposure for a person.
          Map.entry(BOOKED, EnumSet.of(COMPLETED, CANCELLING)),
          Map.entry(CANCELLING, EnumSet.of(CANCELLED)),
          Map.entry(COMPLETED, EnumSet.noneOf(TripStatus.class)),
          Map.entry(CANCELLED, EnumSet.noneOf(TripStatus.class)),
          Map.entry(FAILED, EnumSet.noneOf(TripStatus.class)));

  public boolean canTransitionTo(TripStatus next) {
    return TRANSITIONS.get(this).contains(next);
  }

  public boolean isTerminal() {
    return TRANSITIONS.get(this).isEmpty();
  }
}
