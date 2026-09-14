package io.travelos.workflows.recovery;

import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import io.travelos.workflows.DisruptionRecovery;
import org.jspecify.annotations.Nullable;

/** One recovery per disruption (workflow id = disruption id). See {@link RecoveryWorkflowImpl}. */
@WorkflowInterface
public interface RecoveryWorkflow {

  /**
   * @param finalStatus RESOLVED | NO_ALTERNATIVE | FAILED | MANUAL_INTERVENTION_REQUIRED
   */
  record Outcome(
      String disruptionId,
      String finalStatus,
      @Nullable String orderId,
      @Nullable String replacementBundleId,
      @Nullable String failureStage,
      @Nullable String failureCode) {}

  @WorkflowMethod(name = DisruptionRecovery.WORKFLOW_TYPE)
  Outcome run(DisruptionRecovery.Input input);

  @SignalMethod(name = DisruptionRecovery.SIGNAL_APPROVAL_DECIDED)
  void approvalDecided(DisruptionRecovery.ApprovalDecision decision);

  @QueryMethod(name = DisruptionRecovery.QUERY_STAGE)
  DisruptionRecovery.Stage stage();
}
