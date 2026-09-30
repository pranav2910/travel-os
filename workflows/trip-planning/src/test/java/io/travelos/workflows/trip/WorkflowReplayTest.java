package io.travelos.workflows.trip;

import io.temporal.testing.WorkflowReplayer;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Temporal re-executes a running execution's recorded history against the current code every time
 * the execution wakes up, so a workflow change must keep every command an earlier version recorded,
 * in order. Otherwise every execution in flight at deployment time is stuck with a non-determinism
 * failure, and an approval can keep one in flight for days: five trips from the 2026-09-23 QA run
 * were stranded exactly so when the completion program's worker replaced the earlier one.
 *
 * <p>These histories were recorded by the worker at commit {@code 61fcf14}, the last one before the
 * completion program, from its own test harness (fake activities, fixture trips; no data from any
 * environment): a round trip and an itinerary, each parked at the approval and each booked after
 * one. A change that breaks them needs a {@code Workflow.getVersion} guard, as {@link
 * TripWorkflowImpl#CHANGE_GOVERNED_PURCHASE} does for Phases 3 to 7. Before changing a workflow
 * again, record fixtures from the released code the same way and add them here.
 */
class WorkflowReplayTest {

  @ParameterizedTest
  @ValueSource(
      strings = {
        "round-trip-awaiting-approval-61fcf14",
        "round-trip-booked-61fcf14",
        "itinerary-awaiting-approval-61fcf14",
        "itinerary-booked-61fcf14"
      })
  void anExecutionRecordedBeforeTheCompletionProgramReplaysOnThisWorker(String fixture)
      throws Exception {
    WorkflowReplayer.replayWorkflowExecution(history(fixture), TripWorkflowImpl.class);
  }

  private static String history(String fixture) throws Exception {
    try (InputStream in =
        new GZIPInputStream(
            WorkflowReplayTest.class.getResourceAsStream("/replay/" + fixture + ".json.gz"))) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
