package io.travelos.workflows.demand;

import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import io.travelos.workflows.DemandSync;

@WorkflowInterface
public interface DemandSyncWorkflow {
  @WorkflowMethod(name = DemandSync.WORKFLOW_TYPE)
  DemandSync.Outcome run(DemandSync.Input input);

  @QueryMethod(name = DemandSync.QUERY_STAGE)
  String stage();
}
