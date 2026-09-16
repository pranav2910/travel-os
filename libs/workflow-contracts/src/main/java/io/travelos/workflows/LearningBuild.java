package io.travelos.workflows;

/**
 * The contract of the profile build workflow (Slice 5): one versioned profile, built and evaluated
 * through the Learning service in idempotent steps. Workflow id = profile id, so a redelivered
 * build-requested event never starts a second build of the same profile.
 */
public final class LearningBuild {
  public static final String TASK_QUEUE = "learning-build";
  public static final String WORKFLOW_TYPE = "LearningBuildWorkflow";
  public static final String AGENT = "agent/learning-build/v1";
  public static final String QUERY_STAGE = "stage";

  private LearningBuild() {}

  public static String workflowId(String profileId) {
    return profileId;
  }

  public record Input(String tenantId, String profileId) {}

  public record Outcome(String profileId, String status, String verdict, String failureCode) {}
}
