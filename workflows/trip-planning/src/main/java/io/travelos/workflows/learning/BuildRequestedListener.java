package io.travelos.workflows.learning;

import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import io.travelos.events.EventCodec;
import io.travelos.events.EventEnvelope;
import io.travelos.workflows.LearningBuild;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * "A profile build was requested" starts its workflow. Kafka delivers at least once; the workflow
 * id equals the profile id, so a redelivery is a no-op instead of a second build.
 */
@Component
public class BuildRequestedListener {
  private static final Logger log = LoggerFactory.getLogger(BuildRequestedListener.class);
  private final WorkflowClient client;
  private final EventCodec codec = new EventCodec();

  public BuildRequestedListener(WorkflowClient client) {
    this.client = client;
  }

  @KafkaListener(topics = "travel.learning", groupId = "${spring.kafka.consumer.group-id}")
  public void onLearningEvent(String payload) {
    EventEnvelope event;
    try {
      event = codec.fromJson(payload);
    } catch (RuntimeException e) {
      log.error("unreadable event on travel.learning, skipping: {}", e.getMessage());
      return;
    }
    if (!"travel.learning.build-requested".equals(event.eventType())) {
      return;
    }
    start(event.tenantId(), String.valueOf(event.data().get("profileId")));
  }

  public void start(String tenantId, String profileId) {
    LearningBuildWorkflow workflow =
        client.newWorkflowStub(
            LearningBuildWorkflow.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(LearningBuild.TASK_QUEUE)
                .setWorkflowId(LearningBuild.workflowId(profileId))
                .setWorkflowIdReusePolicy(
                    WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
                .build());
    try {
      WorkflowClient.start(workflow::run, new LearningBuild.Input(tenantId, profileId));
      log.info(
          "started {} for profile {} (tenant {})",
          LearningBuild.WORKFLOW_TYPE,
          profileId,
          tenantId);
    } catch (WorkflowExecutionAlreadyStarted e) {
      log.info("workflow for profile {} already exists; ignoring redelivery", profileId);
    }
  }
}
