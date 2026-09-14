package io.travelos.workflows.recovery;

import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import io.travelos.events.EventCodec;
import io.travelos.events.EventEnvelope;
import io.travelos.workflows.DisruptionRecovery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Starts one recovery per confirmed disruption. Kafka may deliver the event twice; the workflow id
 * (= disruption id) with REJECT_DUPLICATE makes the second start a no-op, never a second recovery.
 */
@Component
public class ImpactConfirmedListener {

  private static final Logger log = LoggerFactory.getLogger(ImpactConfirmedListener.class);
  private final WorkflowClient client;
  private final EventCodec codec = new EventCodec();

  public ImpactConfirmedListener(WorkflowClient client) {
    this.client = client;
  }

  @KafkaListener(topics = "travel.disruption", groupId = "${spring.kafka.consumer.group-id}")
  public void onDisruptionEvent(String payload) {
    EventEnvelope event;
    try {
      event = codec.fromJson(payload);
    } catch (RuntimeException e) {
      log.error("unreadable event on travel.disruption, skipping: {}", e.getMessage());
      return;
    }
    if (!"travel.disruption.impact-confirmed".equals(event.eventType())) {
      return;
    }
    start(event.tenantId(), String.valueOf(event.data().get("disruptionId")));
  }

  public void start(String tenantId, String disruptionId) {
    RecoveryWorkflow workflow =
        client.newWorkflowStub(
            RecoveryWorkflow.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(DisruptionRecovery.TASK_QUEUE)
                .setWorkflowId(DisruptionRecovery.workflowId(disruptionId))
                .setWorkflowIdReusePolicy(
                    WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
                .build());
    try {
      WorkflowClient.start(workflow::run, new DisruptionRecovery.Input(tenantId, disruptionId));
      log.info(
          "started {} for disruption {} (tenant {})",
          DisruptionRecovery.WORKFLOW_TYPE,
          disruptionId,
          tenantId);
    } catch (WorkflowExecutionAlreadyStarted e) {
      log.info("recovery for {} already exists; ignoring redelivery", disruptionId);
    }
  }
}
