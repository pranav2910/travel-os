package io.travelos.assistance.model;

/** What kind of unfinished business a case is. A fixed vocabulary: it is a metrics label. */
public enum CaseKind {
  /** Money is at risk at a supplier: a refused release, a failed compensation. */
  EXPOSURE,
  /** A supplier may or may not have booked; nobody knows until a person checks. */
  OUTCOME_UNKNOWN,
  /** A trip's cancellation could not be completed by machine. */
  CANCELLATION_INCOMPLETE,
  /** A disruption recovery is waiting on an approver while a traveler is affected. */
  RECOVERY_APPROVAL,
  /** Phase 7: a trip's approval step nobody answered in time, escalated to a travel admin. */
  APPROVAL_ESCALATED,
  /** The automated recovery found nothing; the traveler must be rebooked by hand. */
  RECOVERY_FAILED,
  /** A booking failed after approval; the traveler needs a new plan. */
  BOOKING_FAILED,
  /** The payment was declined; the trip cannot be bought as planned. */
  PAYMENT_DECLINED,
  /** A traveler asked for something a person must do. */
  TRAVELER_REQUEST,
  /** A traveler's safety: a check-in, an advisory, an emergency. */
  SAFETY,
  OTHER
}
