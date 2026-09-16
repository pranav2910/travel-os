package io.travelos.workflows.demand;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.grpc.Status;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import io.travelos.contracts.context.v1.SyncPageResponse;
import io.travelos.contracts.context.v1.SyncRun;
import io.travelos.workflows.DemandSync;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The sync workflow: pages until done, waits out retryable trouble, records final failures. */
class DemandSyncWorkflowTest {
  private TestWorkflowEnvironment env;
  private WorkflowClient client;
  private DemandActivities activities;

  @BeforeEach
  void setUp() {
    env = TestWorkflowEnvironment.newInstance();
    Worker worker = env.newWorker(DemandSync.TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(DemandSyncWorkflowImpl.class);
    activities = mock(DemandActivities.class);
    worker.registerActivitiesImplementations(activities);
    env.start();
    client = env.getWorkflowClient();
  }

  @AfterEach
  void tearDown() {
    env.close();
  }

  @Test
  void pagesUntilTheSourceSaysDoneThenCompletes() {
    doAnswer(inv -> page("p:1", false, 2))
        .doAnswer(inv -> page("p:2", false, 2))
        .doAnswer(inv -> page("p:3", true, 1))
        .when(activities)
        .syncPage(anyString(), anyString(), anyString(), anyString());
    doAnswer(inv -> SyncRun.newBuilder().setRunId("syn_1").setStatus("COMPLETED").build())
        .when(activities)
        .completeSync(anyString(), anyString(), anyString());
    DemandSync.Outcome outcome = run("syn_01ARZ3NDEKTSV4RRFFQ69G5FB5");
    assertThat(outcome.status()).isEqualTo("COMPLETED");
    assertThat(outcome.pages()).isEqualTo(3);
    verify(activities).syncPage("acme", "cnx_1", "syn_01ARZ3NDEKTSV4RRFFQ69G5FB5", "");
    verify(activities).syncPage("acme", "cnx_1", "syn_01ARZ3NDEKTSV4RRFFQ69G5FB5", "p:1");
    verify(activities).syncPage("acme", "cnx_1", "syn_01ARZ3NDEKTSV4RRFFQ69G5FB5", "p:2");
    verify(activities).completeSync("acme", "cnx_1", "syn_01ARZ3NDEKTSV4RRFFQ69G5FB5");
    verify(activities, never()).failSync(anyString(), anyString(), anyString(), anyString(), any());
  }

  @Test
  void anOutageAndARateLimitAreWaitedOutNotMistakenForFailures() {
    AtomicInteger calls = new AtomicInteger();
    doAnswer(
            inv -> {
              int n = calls.incrementAndGet();
              if (n == 1) {
                throw Status.UNAVAILABLE
                    .withDescription("SOURCE_UNAVAILABLE: outage")
                    .asRuntimeException();
              }
              if (n == 2) {
                throw Status.RESOURCE_EXHAUSTED
                    .withDescription("RATE_LIMITED: back off")
                    .asRuntimeException();
              }
              return page("p:1", true, 3);
            })
        .when(activities)
        .syncPage(anyString(), anyString(), anyString(), anyString());
    doAnswer(inv -> SyncRun.newBuilder().setRunId("syn_2").setStatus("COMPLETED").build())
        .when(activities)
        .completeSync(anyString(), anyString(), anyString());
    DemandSync.Outcome outcome = run("syn_01ARZ3NDEKTSV4RRFFQ69G5FB6");
    assertThat(outcome.status()).isEqualTo("COMPLETED");
    assertThat(calls.get()).isEqualTo(3);
    verify(activities, times(3)).syncPage(anyString(), anyString(), anyString(), anyString());
  }

  @Test
  void aFinalAnswerFailsTheRunHonestlyWithItsCode() {
    doThrow(
            ApplicationFailure.newNonRetryableFailure(
                "FAILED_PRECONDITION: RUN_BUSY: another run is in flight", "FAILED_PRECONDITION"))
        .when(activities)
        .syncPage(anyString(), anyString(), anyString(), anyString());
    doAnswer(
            inv ->
                SyncRun.newBuilder()
                    .setRunId("syn_3")
                    .setStatus("FAILED")
                    .setFailureCode("RUN_BUSY")
                    .build())
        .when(activities)
        .failSync(anyString(), anyString(), anyString(), anyString(), any());
    DemandSync.Outcome outcome = run("syn_01ARZ3NDEKTSV4RRFFQ69G5FB7");
    assertThat(outcome.status()).isEqualTo("FAILED");
    assertThat(outcome.failureCode()).isEqualTo("RUN_BUSY");
    verify(activities)
        .failSync(
            eq("acme"), eq("cnx_1"), eq("syn_01ARZ3NDEKTSV4RRFFQ69G5FB7"), eq("RUN_BUSY"), any());
    verify(activities, never()).completeSync(anyString(), anyString(), anyString());
  }

  private DemandSync.Outcome run(String runId) {
    DemandSyncWorkflow workflow =
        client.newWorkflowStub(
            DemandSyncWorkflow.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(DemandSync.TASK_QUEUE)
                .setWorkflowId(DemandSync.workflowId(runId))
                .build());
    return workflow.run(new DemandSync.Input("acme", "cnx_1", runId));
  }

  private static SyncPageResponse page(String next, boolean done, int seen) {
    return SyncPageResponse.newBuilder()
        .setNextCursor(next)
        .setDone(done)
        .setItemsSeen(seen)
        .setItemsChanged(seen)
        .build();
  }
}
