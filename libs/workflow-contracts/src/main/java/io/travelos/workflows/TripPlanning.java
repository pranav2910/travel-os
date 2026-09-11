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
    SEARCHING,
    EVALUATING_POLICY,
    OPTIMIZING,
    AWAITING_APPROVAL,
    BOOKING,
    BOOKED,
    FAILED,
    CANCELLED
  }
}
