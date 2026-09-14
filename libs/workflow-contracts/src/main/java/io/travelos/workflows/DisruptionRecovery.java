package io.travelos.workflows;

import org.jspecify.annotations.Nullable;

/**
 * The contract between the disruption-recovery workflow and everything that talks to it: the
 * Disruption service signals approvals by these names; the worker registers under them.
 */
public final class DisruptionRecovery {

  public static final String TASK_QUEUE = "disruption-recovery";
  public static final String WORKFLOW_TYPE = "DisruptionRecoveryWorkflow";

  /** Bumped when the workflow's step sequence changes; recorded in every decision record. */
  public static final String WORKFLOW_VERSION = "1";

  public static final String AGENT = "agent/disruption-recovery/v1";

  /** Workflow id = disruption id: at most one recovery per disruption, ever. */
  public static String workflowId(String disruptionId) {
    return disruptionId;
  }

  public static final String SIGNAL_APPROVAL_DECIDED = "approvalDecided";
  public static final String QUERY_STAGE = "stage";

  private DisruptionRecovery() {}

  public record Input(String tenantId, String disruptionId) {}

  /**
   * @param decision APPROVED | REJECTED
   * @param decidedBy canonical principal id, e.g. human/bob
   */
  public record ApprovalDecision(
      String approvalId, String decision, String decidedBy, @Nullable String comment) {}

  /** Where the recovery is, for the explainability API. Mirrors DisruptionStatus. */
  public enum Stage {
    LOADING,
    SEARCHING_ALTERNATIVES,
    EVALUATING_POLICY,
    OPTIMIZING,
    DECIDING,
    AWAITING_APPROVAL,
    CHANGING,
    RESOLVED,
    NO_ALTERNATIVE,
    FAILED,
    MANUAL_INTERVENTION_REQUIRED
  }
}
