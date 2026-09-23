package io.travelos.workflows.trip;

import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.failure.TimeoutFailure;

/**
 * The stable failure code a trip records when an activity gives up. A gRPC status the adapter
 * classified ({@link TripActivitiesImpl#call}) is reported as ACTIVITY_&lt;STATUS&gt;; an exception
 * class name (what Temporal uses as the type of an unclassified error) is never a code a person can
 * act on, so it becomes PLATFORM_ERROR and the message keeps the detail.
 */
final class FailureCodes {
  private FailureCodes() {}

  static String of(ActivityFailure e) {
    if (e.getCause() instanceof ApplicationFailure af
        && af.getType() != null
        && !af.getType().isBlank()) {
      return looksLikeClassName(af.getType()) ? "PLATFORM_ERROR" : "ACTIVITY_" + af.getType();
    }
    if (e.getCause() instanceof TimeoutFailure) {
      return "ACTIVITY_TIMED_OUT";
    }
    return "ACTIVITY_FAILED";
  }

  /** The human-readable detail behind the code, without Temporal's own wrapping. */
  static String message(ActivityFailure e) {
    if (e.getCause() instanceof ApplicationFailure af && af.getOriginalMessage() != null) {
      return af.getOriginalMessage();
    }
    return e.getCause() == null ? e.getMessage() : e.getCause().getMessage();
  }

  static boolean looksLikeClassName(String type) {
    return type.contains(".") || type.endsWith("Exception") || type.endsWith("Error");
  }
}
