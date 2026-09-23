package io.travelos.workflows.trip;

import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import io.travelos.workflows.TripCancellation;
import org.jspecify.annotations.Nullable;

/** One per cancelled booked trip (workflow id = cancel:tripId). See the implementation. */
@WorkflowInterface
public interface TripCancellationWorkflow {

  /**
   * @param finalStatus CANCELLED, or CANCELLING when the trip is left with a person; otherwise the
   *     trip's status when there was nothing to do
   * @param failureCode why the trip is still CANCELLING, when it is
   */
  record Outcome(String tripId, String finalStatus, @Nullable String failureCode) {}

  @WorkflowMethod(name = TripCancellation.WORKFLOW_TYPE)
  Outcome run(TripCancellation.Input input);

  @QueryMethod(name = TripCancellation.QUERY_STAGE)
  TripCancellation.Stage stage();
}
