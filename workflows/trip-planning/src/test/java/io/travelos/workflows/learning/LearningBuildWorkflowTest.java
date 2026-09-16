package io.travelos.workflows.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import io.travelos.contracts.learning.v1.ProfileSummary;
import io.travelos.workflows.LearningBuild;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LearningBuildWorkflowTest {
  static final String PROFILE = "lp_01ARZ3NDEKTSV4RRFFQ69G5FAV";
  private TestWorkflowEnvironment env;
  private LearningActivities activities;
  private WorkflowClient client;

  @BeforeEach
  void setUp() {
    env = TestWorkflowEnvironment.newInstance();
    Worker worker = env.newWorker(LearningBuild.TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(LearningBuildWorkflowImpl.class);
    activities = mock(LearningActivities.class);
    worker.registerActivitiesImplementations(activities);
    env.start();
    client = env.getWorkflowClient();
    when(activities.beginBuild(anyString(), anyString())).thenReturn(summary("BUILDING", ""));
    when(activities.computeProfile(anyString(), anyString())).thenReturn(summary("BUILT", ""));
    when(activities.evaluateProfile(anyString(), anyString()))
        .thenReturn(summary("ELIGIBLE", "PASSED"));
  }

  @AfterEach
  void tearDown() {
    env.close();
  }

  @Test
  void buildsAndEvaluatesInOrder() {
    LearningBuild.Outcome outcome = run();
    assertThat(outcome.status()).isEqualTo("ELIGIBLE");
    assertThat(outcome.verdict()).isEqualTo("PASSED");
    verify(activities, times(1)).beginBuild("acme", PROFILE);
    verify(activities, times(1)).computeProfile("acme", PROFILE);
    verify(activities, times(1)).evaluateProfile("acme", PROFILE);
    verify(activities, never()).failBuild(anyString(), anyString(), anyString(), anyString());
  }

  @Test
  void aTransientOutageIsRetriedAndOneProfileResults() {
    when(activities.computeProfile(anyString(), anyString()))
        .thenThrow(new RuntimeException("learning restarting"))
        .thenReturn(summary("BUILT", ""));
    LearningBuild.Outcome outcome = run();
    assertThat(outcome.status()).isEqualTo("ELIGIBLE");
    verify(activities, times(2)).computeProfile("acme", PROFILE);
    verify(activities, times(1)).evaluateProfile("acme", PROFILE);
  }

  @Test
  void aFinalFailureIsRecordedOnTheProfileAndNothingElse() {
    when(activities.evaluateProfile(anyString(), anyString()))
        .thenThrow(
            ApplicationFailure.newNonRetryableFailure("INTERNAL: DATASET_CORRUPT: x", "INTERNAL"));
    when(activities.failBuild(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(summary("FAILED", ""));
    LearningBuild.Outcome outcome = run();
    assertThat(outcome.status()).isEqualTo("FAILED");
    assertThat(outcome.failureCode()).isEqualTo("DATASET_CORRUPT");
    verify(activities)
        .failBuild("acme", PROFILE, "DATASET_CORRUPT", "INTERNAL: DATASET_CORRUPT: x");
  }

  @Test
  void anAlreadyFinishedProfileIsReportedNotRebuilt() {
    when(activities.beginBuild(anyString(), anyString()))
        .thenReturn(summary("REJECTED", "INSUFFICIENT_EVIDENCE"));
    LearningBuild.Outcome outcome = run();
    assertThat(outcome.status()).isEqualTo("REJECTED");
    verify(activities, never()).computeProfile(anyString(), anyString());
  }

  private LearningBuild.Outcome run() {
    LearningBuildWorkflow workflow =
        client.newWorkflowStub(
            LearningBuildWorkflow.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(LearningBuild.TASK_QUEUE)
                .setWorkflowId(PROFILE)
                .build());
    return workflow.run(new LearningBuild.Input("acme", PROFILE));
  }

  private static ProfileSummary summary(String status, String verdict) {
    return ProfileSummary.newBuilder()
        .setProfileId(PROFILE)
        .setStatus(status)
        .setVerdict(verdict)
        .build();
  }
}
