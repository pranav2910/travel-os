package io.travelos.workflows.learning;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.workflow.Workflow;
import io.travelos.contracts.optimization.v1.LearningInputs;
import java.time.Duration;
import org.slf4j.Logger;

/**
 * How a planning workflow obtains its learning inputs: one bounded activity call, pinned to the
 * attempt by the workflow history, or the named fallback that reproduces the baseline when the
 * Learning service cannot answer in time. Learning is optional by design: a planner never waits out
 * a learning outage, and never asks twice.
 */
public final class LearningResolution {
  public static final String UNAVAILABLE = "LEARNING_UNAVAILABLE";

  private LearningResolution() {}

  public static LearningActivities stub() {
    return Workflow.newActivityStub(
        LearningActivities.class,
        ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(20))
            .setRetryOptions(
                RetryOptions.newBuilder()
                    .setInitialInterval(Duration.ofSeconds(1))
                    .setBackoffCoefficient(2.0)
                    .setMaximumInterval(Duration.ofSeconds(5))
                    .setMaximumAttempts(3)
                    .build())
            .build());
  }

  public static LearningInputs resolve(
      LearningActivities learning,
      Logger log,
      String tenant,
      String tripId,
      String travelerId,
      String purpose) {
    try {
      LearningInputs inputs = learning.resolve(tenant, tripId, travelerId, purpose);
      return inputs == null ? fallback() : inputs;
    } catch (ActivityFailure e) {
      log.warn("learning unavailable for {} ({}); planning with the baseline", tripId, purpose);
      return fallback();
    }
  }

  public static LearningInputs fallback() {
    return LearningInputs.newBuilder().setMode("OFF").setFallbackReason(UNAVAILABLE).build();
  }
}
