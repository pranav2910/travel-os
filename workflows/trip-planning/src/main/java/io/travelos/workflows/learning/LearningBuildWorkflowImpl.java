package io.travelos.workflows.learning;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.workflow.Workflow;
import io.travelos.contracts.learning.v1.ProfileSummary;
import io.travelos.workflows.LearningBuild;
import java.time.Duration;
import org.slf4j.Logger;

/**
 * One profile: begin -> compute -> evaluate, each an idempotent activity against the Learning
 * service, so a worker that dies between steps resumes on the next worker with one profile and no
 * partial artifact. A failure that survives its retries is recorded on the profile (FAILED); the
 * tenant's active profile is never involved.
 */
public class LearningBuildWorkflowImpl implements LearningBuildWorkflow {
  private static final Logger log = Workflow.getLogger(LearningBuildWorkflowImpl.class);

  private final LearningActivities activities =
      Workflow.newActivityStub(
          LearningActivities.class,
          ActivityOptions.newBuilder()
              .setStartToCloseTimeout(Duration.ofMinutes(5))
              .setRetryOptions(
                  RetryOptions.newBuilder()
                      .setInitialInterval(Duration.ofSeconds(1))
                      .setBackoffCoefficient(2.0)
                      .setMaximumInterval(Duration.ofSeconds(30))
                      // ~8 minutes of retries: a Learning service outage shorter than that is a
                      // retry, not a failed build; the steps are idempotent so nothing is repeated
                      .setMaximumAttempts(20)
                      .build())
              .build());

  private String stage = "REQUESTED";

  @Override
  public LearningBuild.Outcome run(LearningBuild.Input input) {
    String tenant = input.tenantId();
    String profileId = input.profileId();
    try {
      stage = "BEGINNING";
      ProfileSummary begun = activities.beginBuild(tenant, profileId);
      if (isFinal(begun.getStatus())) {
        stage = begun.getStatus();
        return new LearningBuild.Outcome(
            profileId, begun.getStatus(), begun.getVerdict(), begun.getFailureCode());
      }
      stage = "COMPUTING";
      ProfileSummary built = activities.computeProfile(tenant, profileId);
      if ("FAILED".equals(built.getStatus())) {
        stage = "FAILED";
        return new LearningBuild.Outcome(profileId, "FAILED", "", built.getFailureCode());
      }
      stage = "EVALUATING";
      ProfileSummary evaluated = activities.evaluateProfile(tenant, profileId);
      stage = evaluated.getStatus();
      return new LearningBuild.Outcome(
          profileId, evaluated.getStatus(), evaluated.getVerdict(), evaluated.getFailureCode());
    } catch (ActivityFailure | ApplicationFailure e) {
      String code = failureCode(e);
      String message = originalMessage(e);
      log.warn("build {} failed at {}: {}", profileId, stage, code);
      stage = "FAILED";
      try {
        activities.failBuild(tenant, profileId, code, message);
      } catch (ActivityFailure recordFailure) {
        log.error("build {}: could not record failure {}", profileId, code);
      }
      return new LearningBuild.Outcome(profileId, "FAILED", "", code);
    }
  }

  static boolean isFinal(String status) {
    return "ELIGIBLE".equals(status) || "REJECTED".equals(status) || "FAILED".equals(status);
  }

  /** The cause's own words (what the Learning service said), not Temporal's wrapper. */
  static String originalMessage(RuntimeException e) {
    Throwable cause = e instanceof ActivityFailure ? e.getCause() : e;
    if (cause instanceof ApplicationFailure af) {
      return af.getOriginalMessage();
    }
    return cause == null || cause.getMessage() == null ? e.getMessage() : cause.getMessage();
  }

  private static String failureCode(RuntimeException e) {
    Throwable cause = e instanceof ActivityFailure ? e.getCause() : e;
    if (cause instanceof ApplicationFailure af) {
      String message = af.getOriginalMessage();
      int colon = message.indexOf(':');
      String detail = colon > 0 ? message.substring(colon + 1).trim() : message;
      int end = detail.indexOf(':');
      return end > 0
          ? detail.substring(0, end)
          : (af.getType() == null ? "BUILD_FAILED" : af.getType());
    }
    return "BUILD_FAILED";
  }

  @Override
  public String stage() {
    return stage;
  }
}
