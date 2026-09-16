package io.travelos.workflows.demand;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.workflow.Workflow;
import io.travelos.contracts.context.v1.SyncPageResponse;
import io.travelos.workflows.DemandSync;
import java.time.Duration;
import org.slf4j.Logger;

/**
 * One connector run: page after page until the source says it is done, then completion. Every page
 * is an activity with its own retries (an outage or a rate limit is a retry with backoff, not a
 * failure), and the cursor lives in the workflow history, so a worker that dies mid-run resumes on
 * the next worker at the page it was on. Enterprise Context makes a repeated page a no-op.
 */
public class DemandSyncWorkflowImpl implements DemandSyncWorkflow {
  private static final Logger log = Workflow.getLogger(DemandSyncWorkflowImpl.class);
  static final int MAX_PAGES = 1000;

  private final DemandActivities activities =
      Workflow.newActivityStub(
          DemandActivities.class,
          ActivityOptions.newBuilder()
              .setStartToCloseTimeout(Duration.ofSeconds(60))
              .setRetryOptions(
                  RetryOptions.newBuilder()
                      .setInitialInterval(Duration.ofSeconds(1))
                      .setBackoffCoefficient(2.0)
                      .setMaximumInterval(Duration.ofSeconds(30))
                      // unbounded attempts: an outage is waited out, never mistaken for a failure
                      .build())
              .build());

  private String stage = "REQUESTED";

  @Override
  public DemandSync.Outcome run(DemandSync.Input input) {
    String cursor = "";
    int pages = 0;
    try {
      while (true) {
        stage = "SYNCING:" + cursor;
        SyncPageResponse page =
            activities.syncPage(input.tenantId(), input.connectorId(), input.runId(), cursor);
        pages++;
        if (page.getDone()) {
          break;
        }
        cursor = page.getNextCursor();
        if (pages >= MAX_PAGES) {
          throw ApplicationFailure.newNonRetryableFailure(
              "TOO_MANY_PAGES: " + pages + " pages", "TOO_MANY_PAGES");
        }
      }
      stage = "COMPLETING";
      activities.completeSync(input.tenantId(), input.connectorId(), input.runId());
      stage = "COMPLETED";
      return new DemandSync.Outcome(input.runId(), "COMPLETED", pages, "");
    } catch (ActivityFailure | ApplicationFailure e) {
      String code = failureCode(e);
      log.warn("sync {} failed after {} pages: {}", input.runId(), pages, code);
      stage = "FAILED";
      try {
        activities.failSync(
            input.tenantId(), input.connectorId(), input.runId(), code, e.getMessage());
      } catch (ActivityFailure recordFailure) {
        log.error("sync {}: could not record failure {}", input.runId(), code);
      }
      return new DemandSync.Outcome(input.runId(), "FAILED", pages, code);
    }
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
          : (af.getType() == null ? "SYNC_FAILED" : af.getType());
    }
    return "SYNC_FAILED";
  }

  @Override
  public String stage() {
    return stage;
  }
}
