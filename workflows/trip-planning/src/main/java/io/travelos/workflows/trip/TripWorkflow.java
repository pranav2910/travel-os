package io.travelos.workflows.trip;

import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import io.travelos.workflows.TripPlanning;
import org.jspecify.annotations.Nullable;

/** One workflow per trip (workflow id = trip id). See {@link TripWorkflowImpl} for the steps. */
@WorkflowInterface
public interface TripWorkflow {

  /**
   * @param finalStatus BOOKED | FAILED | CANCELLED (the trip's status when the workflow ended)
   */
  record Outcome(
      String tripId,
      String finalStatus,
      @Nullable String orderId,
      @Nullable String failureStage,
      @Nullable String failureCode) {}

  @WorkflowMethod(name = TripPlanning.WORKFLOW_TYPE)
  Outcome run(TripPlanning.Input input);

  @SignalMethod(name = TripPlanning.SIGNAL_APPROVAL_DECIDED)
  void approvalDecided(TripPlanning.ApprovalDecision decision);

  @SignalMethod(name = TripPlanning.SIGNAL_CANCELLED)
  void cancelled(String reason);

  /** Phase 3: a person authorized the purchase of the quoted plan (durable in Travel Core). */
  @SignalMethod(name = TripPlanning.SIGNAL_PURCHASE_AUTHORIZED)
  void purchaseAuthorized(TripPlanning.PurchaseAuthorized authorized);

  /** Phase 3: a person chose another quoted alternative. */
  @SignalMethod(name = TripPlanning.SIGNAL_SELECTION_CHANGED)
  void selectionChanged(TripPlanning.SelectionChanged selection);

  /** Phase 3: a person asked for a fresh price. */
  @SignalMethod(name = TripPlanning.SIGNAL_QUOTE_REFRESH)
  void refreshQuote(String reason);

  @QueryMethod(name = TripPlanning.QUERY_STAGE)
  TripPlanning.Stage stage();
}
