package io.travelos.workflows.learning;

import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import io.travelos.workflows.LearningBuild;

@WorkflowInterface
public interface LearningBuildWorkflow {
  @WorkflowMethod(name = LearningBuild.WORKFLOW_TYPE)
  LearningBuild.Outcome run(LearningBuild.Input input);

  @QueryMethod(name = LearningBuild.QUERY_STAGE)
  String stage();
}
