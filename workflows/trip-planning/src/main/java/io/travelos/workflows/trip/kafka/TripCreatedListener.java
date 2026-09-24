package io.travelos.workflows.trip.kafka;

import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import io.travelos.events.EventCodec;
import io.travelos.events.EventEnvelope;
import io.travelos.workflows.TripCancellation;
import io.travelos.workflows.TripPlanning;
import io.travelos.workflows.trip.TripCancellationWorkflow;
import io.travelos.workflows.trip.TripWorkflow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * "A trip was created" starts its planning workflow; "a booked trip's cancellation was requested"
 * starts its release workflow. Kafka delivers at least once; the workflow ids are derived from the
 * trip id, so a redelivery is a no-op instead of a second plan or a second release.
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
    String tripId = String.valueOf(event.data().get("tripId"));
    switch (event.eventType()) {
      case "travel.trip.created" -> start(event.tenantId(), tripId);
      case "travel.trip.cancellation-requested" ->
          startCancellation(
              event.tenantId(),
              tripId,
              String.valueOf(event.data().getOrDefault("orderId", "")),
              String.valueOf(event.data().getOrDefault("reason", "")),
              String.valueOf(event.data().getOrDefault("requestedBy", "")));
      case "travel.trip.component-cancellation-requested" -> {
        Object ids = event.data().get("componentIds");
        java.util.List<String> componentIds = new java.util.ArrayList<>();
        if (ids instanceof java.util.List<?> list) {
          list.forEach(o -> componentIds.add(String.valueOf(o)));
        }
        startComponentCancellation(
            event.tenantId(),
            tripId,
            String.valueOf(event.data().getOrDefault("orderId", "")),
            String.valueOf(event.data().getOrDefault("reason", "")),
            String.valueOf(event.data().getOrDefault("requestedBy", "")),
            componentIds);
      }
      default -> {}
    }
  }

  /** A booked trip's cancellation: one release workflow per trip, redeliveries ignored. */
  public void startCancellation(
      String tenantId, String tripId, String orderId, String reason, String requestedBy) {
    TripCancellationWorkflow workflow =
        client.newWorkflowStub(
            TripCancellationWorkflow.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(TripCancellation.TASK_QUEUE)
                .setWorkflowId(TripCancellation.workflowId(tripId))
                .setWorkflowIdReusePolicy(
                    WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
                .build());
    try {
      WorkflowClient.start(
          workflow::run,
          new TripCancellation.Input(tenantId, tripId, orderId, reason, requestedBy));
      log.info(
          "started {} for trip {} order {} (tenant {})",
          TripCancellation.WORKFLOW_TYPE,
          tripId,
          orderId,
          tenantId);
    } catch (WorkflowExecutionAlreadyStarted e) {
      log.info("cancellation for trip {} already exists; ignoring redelivery", tripId);
    }
  }

  /** Phase 6: a component-scoped release; the trip stays BOOKED for the rest. */
  public void startComponentCancellation(
      String tenantId,
      String tripId,
      String orderId,
      String reason,
      String requestedBy,
      java.util.List<String> componentIds) {
    if (componentIds.isEmpty()) {
      startCancellation(tenantId, tripId, orderId, reason, requestedBy);
      return;
    }
    TripCancellationWorkflow workflow =
        client.newWorkflowStub(
            TripCancellationWorkflow.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(TripCancellation.TASK_QUEUE)
                .setWorkflowId(TripCancellation.workflowId(tripId, componentIds))
                .setWorkflowIdReusePolicy(
                    WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
                .build());
    try {
      WorkflowClient.start(
          workflow::run,
          new TripCancellation.Input(tenantId, tripId, orderId, reason, requestedBy, componentIds));
      log.info(
          "started component release for trip {} components {} (tenant {})",
          tripId,
          componentIds,
          tenantId);
    } catch (WorkflowExecutionAlreadyStarted e) {
      log.info(
          "component release for trip {} {} already exists; ignoring redelivery",
          tripId,
          componentIds);
    }
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
