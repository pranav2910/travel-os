package io.travelos.travelcore.approval;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowNotFoundException;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.travelos.workflows.TripPlanning;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Temporal client wiring. Disabled in tests, where a recording signaler stands in. */
@Configuration(proxyBeanMethods = false)
class TemporalConfiguration {

  private static final Logger log = LoggerFactory.getLogger(TemporalConfiguration.class);

  @Bean
  @ConditionalOnProperty(
      prefix = "travelos.temporal",
      name = "enabled",
      havingValue = "true",
      matchIfMissing = true)
  WorkflowClient workflowClient(
      @org.springframework.beans.factory.annotation.Value(
              "${travelos.temporal.address:localhost:7233}")
          String address,
      @org.springframework.beans.factory.annotation.Value("${travelos.temporal.namespace:travelos}")
          String namespace) {
    WorkflowServiceStubs stubs =
        WorkflowServiceStubs.newServiceStubs(
            WorkflowServiceStubsOptions.newBuilder().setTarget(address).build());
    return WorkflowClient.newInstance(
        stubs, WorkflowClientOptions.newBuilder().setNamespace(namespace).build());
  }

  @Bean
  @ConditionalOnMissingBean(ApprovalSignaler.class)
  @ConditionalOnProperty(
      prefix = "travelos.temporal",
      name = "enabled",
      havingValue = "true",
      matchIfMissing = true)
  ApprovalSignaler temporalApprovalSignaler(WorkflowClient client) {
    return (tripId, decision) -> {
      try {
        client
            .newUntypedWorkflowStub(TripPlanning.workflowId(tripId))
            .signal(TripPlanning.SIGNAL_APPROVAL_DECIDED, decision);
      } catch (WorkflowNotFoundException e) {
        // The decision is durable; a workflow that starts later reads it instead of waiting.
        log.warn(
            "no running workflow for {}; approval {} recorded without signal",
            tripId,
            decision.approvalId());
      }
    };
  }

  @Bean
  @ConditionalOnMissingBean(ApprovalSignaler.class)
  @ConditionalOnProperty(prefix = "travelos.temporal", name = "enabled", havingValue = "false")
  ApprovalSignaler loggingApprovalSignaler() {
    return (tripId, decision) ->
        log.info(
            "temporal disabled: approval {} for {} not signalled", decision.approvalId(), tripId);
  }
}
