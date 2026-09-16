package io.travelos.learning.model;

/**
 * The vocabulary of outcomes, each distinct and none inferred from another. {@link Quality} says
 * how reliability-v1 reads the outcome for its supplier key; NEUTRAL outcomes are recorded and
 * shown, never counted as supplier quality.
 */
public enum OutcomeKind {
  /** The supplier confirmed an item the platform booked. */
  BOOKING_CONFIRMED(Quality.SUCCESS),
  /** The supplier refused or lost the booking (seat gone, offer expired, property refused). */
  BOOKING_FAILED_SUPPLIER(Quality.FAILURE),
  /** The platform or the transport failed (timeout, unavailable, payment): not supplier quality. */
  BOOKING_FAILED_PLATFORM(Quality.NEUTRAL),
  /** The traveler or an arranger cancelled. Not supplier quality. */
  CANCELLED_BY_TRAVELER(Quality.NEUTRAL),
  /** The supplier itself cancelled or materially changed a confirmed service. */
  SUPPLIER_DISRUPTION(Quality.FAILURE),
  /** Disruption recovery finished (the platform's outcome, not the supplier's). */
  RECOVERY_RESOLVED(Quality.NEUTRAL),
  RECOVERY_FAILED(Quality.NEUTRAL),
  /** Compensation released an item after a later failure. */
  COMPENSATION_RELEASED(Quality.NEUTRAL),
  /** The supplier refused to release a held or confirmed item. */
  COMPENSATION_REFUSED(Quality.FAILURE),
  /** A person closed a financial exposure. Says nothing about money received. */
  EXPOSURE_RESOLVED(Quality.NEUTRAL),
  /** Finance recorded a settled refund explicitly. Never inferred from a cancellation. */
  REFUND_SETTLED(Quality.NEUTRAL),
  /** The traveler (or an admin) attested that the trip happened. */
  TRIP_COMPLETED(Quality.SUCCESS),
  /** Structured traveler feedback; feeds traveler preference, not supplier reliability. */
  FEEDBACK(Quality.NEUTRAL);

  public enum Quality {
    SUCCESS,
    FAILURE,
    NEUTRAL
  }

  private final Quality quality;

  OutcomeKind(Quality quality) {
    this.quality = quality;
  }

  public Quality quality() {
    return quality;
  }
}
