package io.travelos.workflows.demand;

import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import io.travelos.events.EventCodec;
import io.travelos.events.EventEnvelope;
import io.travelos.workflows.DemandSync;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * "A synchronization was requested" starts its workflow. Kafka delivers at least once; the workflow
 * id equals the run id, so a redelivery is a no-op instead of a second run.
 */
@Component
public class SyncRequestedListener {
  private static final Logger log = LoggerFactory.getLogger(SyncRequestedListener.class);
  private final WorkflowClient client;
  private final EventCodec codec = new EventCodec();

  public SyncRequestedListener(WorkflowClient client) {
    this.client = client;
  }

  @KafkaListener(topics = "travel.demand", groupId = "${spring.kafka.consumer.group-id}")
  public void onDemandEvent(String payload) {
    EventEnvelope event;
    try {
      event = codec.fromJson(payload);
    } catch (RuntimeException e) {
      log.error("unreadable event on travel.demand, skipping: {}", e.getMessage());
      return;
    }
    if (!"travel.demand.sync-requested".equals(event.eventType())) {
      return;
    }
    start(
        event.tenantId(),
        String.valueOf(event.data().get("connectorId")),
        String.valueOf(event.data().get("runId")));
  }

  public void start(String tenantId, String connectorId, String runId) {
    DemandSyncWorkflow workflow =
        client.newWorkflowStub(
            DemandSyncWorkflow.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(DemandSync.TASK_QUEUE)
                .setWorkflowId(DemandSync.workflowId(runId))
                .setWorkflowIdReusePolicy(
                    WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
                .build());
    try {
      WorkflowClient.start(workflow::run, new DemandSync.Input(tenantId, connectorId, runId));
      log.info(
          "started {} for run {} (connector {}, tenant {})",
          DemandSync.WORKFLOW_TYPE,
          runId,
          connectorId,
          tenantId);
    } catch (WorkflowExecutionAlreadyStarted e) {
      log.info("workflow for run {} already exists; ignoring redelivery", runId);
    }
  }
}
