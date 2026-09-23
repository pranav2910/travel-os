package io.travelos.workflows;

import org.jspecify.annotations.Nullable;

/**
 * The contract between the trip-planning workflow and everything that talks to it. Travel Core
 * signals approvals by these names; the worker registers under these names. Payloads are records
 * serialized as JSON by Temporal's default data converter, so both sides must agree on shape.
 */
public final class TripPlanning {

  public static final String NAMESPACE = "travelos";
  public static final String TASK_QUEUE = "trip-planning";
  public static final String WORKFLOW_TYPE = "TripWorkflow";

  /** Workflow id = trip id: at most one planning workflow per trip, ever. */
  public static String workflowId(String tripId) {
    return tripId;
  }

  public static final String SIGNAL_APPROVAL_DECIDED = "approvalDecided";

  /**
   * The requester withdrew the trip (Travel Core recorded CANCELLED); stop waiting, book nothing.
   */
  public static final String SIGNAL_CANCELLED = "cancelled";

  public static final String QUERY_STAGE = "stage";

  private TripPlanning() {}

  public record Input(String tenantId, String tripId) {}

  /**
   * @param decision APPROVED | REJECTED
   * @param decidedBy canonical principal id, e.g. human/bob
   */
  public record ApprovalDecision(
      String approvalId, String decision, String decidedBy, @Nullable String comment) {}

  /** Where the workflow is, for the explainability API and the UI. */
  public enum Stage {
    LOADING,
    /** Free text is being turned into a structured intent by the LLM gateway. */
    UNDERSTANDING,
    SEARCHING,
    EVALUATING_POLICY,
    OPTIMIZING,
    AWAITING_APPROVAL,
    /**
     * Slice 3: quotes and the approved plan are checked again right before any supplier mutation.
     */
    REVALIDATING,
    BOOKING,
    /** Slice 3: a later component failed; confirmed ones are being released. */
    COMPENSATING,
    BOOKED,
    FAILED,
    CANCELLED
  }
}
