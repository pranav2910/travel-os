package io.travelos.workflows.demand;

import io.temporal.activity.ActivityInterface;
import io.travelos.contracts.context.v1.SyncPageResponse;
import io.travelos.contracts.context.v1.SyncRun;

/**
 * The synchronization's side effects, one gRPC call each. SyncPage is safe to retry: a page that
 * was stored before the worker died is found already at every revision the second time.
 */
@ActivityInterface
public interface DemandActivities {
  SyncPageResponse syncPage(String tenantId, String connectorId, String runId, String cursor);

  SyncRun completeSync(String tenantId, String connectorId, String runId);

  SyncRun failSync(String tenantId, String connectorId, String runId, String code, String message);
}
