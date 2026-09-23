package io.travelos.workflows;

/**
 * The contract of the workflow that releases a booked trip's reservation after its cancellation was
 * requested. Travel Core publishes travel.trip.cancellation-requested; the worker starts one of
 * these per trip and reports back through Travel Core's transitions (CANCELLING stays until every
 * component is released; CANCELLED only then).
 */
public final class TripCancellation {

  /** Shares the planning queue: the same worker, the same activities. */
  public static final String TASK_QUEUE = TripPlanning.TASK_QUEUE;

  public static final String WORKFLOW_TYPE = "TripCancellationWorkflow";
  public static final String QUERY_STAGE = "stage";

  /** At most one cancellation workflow per trip, ever; a redelivered request is a no-op. */
  public static String workflowId(String tripId) {
    return "cancel:" + tripId;
  }

  private TripCancellation() {}

  /**
   * @param requestedBy canonical principal id of the person who asked, e.g. human/alice
   */
  public record Input(
      String tenantId, String tripId, String orderId, String reason, String requestedBy) {}

  public enum Stage {
    LOADING,
    /** The Order service is releasing the components at the suppliers. */
    RELEASING,
    /** A supplier refused; the exposure is with a person and the workflow waits for them. */
    AWAITING_RESOLUTION,
    CANCELLED,
    /** Gave up waiting or the order could not be released at all; the trip stays CANCELLING. */
    INCOMPLETE,
    /** The trip was not CANCELLING when the workflow looked (stale or duplicate request). */
    NOTHING_TO_DO
  }
}
