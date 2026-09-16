package io.travelos.workflows;

/**
 * The contract of the connector synchronization workflow (Slice 4): one run of one connector,
 * driven page by page through Enterprise Context. Workflow id = run id, so a redelivered
 * sync-requested event never starts a second run.
 */
public final class DemandSync {
  public static final String TASK_QUEUE = "demand-sync";
  public static final String WORKFLOW_TYPE = "DemandSyncWorkflow";
  public static final String AGENT = "agent/demand-sync/v1";
  public static final String QUERY_STAGE = "stage";

  private DemandSync() {}

  public static String workflowId(String runId) {
    return runId;
  }

  public record Input(String tenantId, String connectorId, String runId) {}

  public record Outcome(String runId, String status, int pages, String failureCode) {}
}
