package io.travelos.workflows.trip.kafka;

import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import io.travelos.events.EventCodec;
import io.travelos.events.EventEnvelope;
import io.travelos.workflows.TripPlanning;
import io.travelos.workflows.trip.TripWorkflow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * "A trip was created" starts its workflow. Kafka delivers at least once; the workflow id equals
 * the trip id, so a redelivery is a no-op instead of a second plan.
 */
@Component
public class TripCreatedListener {

  private static final Logger log = LoggerFactory.getLogger(TripCreatedListener.class);

  private final WorkflowClient client;
  private final EventCodec codec = new EventCodec();

  public TripCreatedListener(WorkflowClient client) {
    this.client = client;
  }

  @KafkaListener(topics = "travel.trip", groupId = "${spring.kafka.consumer.group-id}")
  public void onTripEvent(String payload) {
    EventEnvelope event;
    try {
      event = codec.fromJson(payload);
    } catch (RuntimeException e) {
      log.error("unreadable event on travel.trip, skipping: {}", e.getMessage());
      return;
    }
    if (!"travel.trip.created".equals(event.eventType())) {
      return;
    }
    String tripId = String.valueOf(event.data().get("tripId"));
    start(event.tenantId(), tripId);
  }

  public void start(String tenantId, String tripId) {
    TripWorkflow workflow =
        client.newWorkflowStub(
            TripWorkflow.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(TripPlanning.TASK_QUEUE)
                .setWorkflowId(TripPlanning.workflowId(tripId))
                .setWorkflowIdReusePolicy(
                    WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
                .build());
    try {
      WorkflowClient.start(workflow::run, new TripPlanning.Input(tenantId, tripId));
      log.info("started {} for trip {} (tenant {})", TripPlanning.WORKFLOW_TYPE, tripId, tenantId);
    } catch (WorkflowExecutionAlreadyStarted e) {
      log.info("workflow for trip {} already exists; ignoring redelivery", tripId);
    }
  }
}
