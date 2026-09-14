package io.travelos.disruption.service;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.opentracingshim.OpenTracingShim;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowNotFoundException;
import io.temporal.opentracing.OpenTracingClientInterceptor;
import io.temporal.opentracing.OpenTracingOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.travelos.workflows.DisruptionRecovery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Temporal client for signalling recovery approvals. Disabled in tests (a recording stand-in). */
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
      ObjectProvider<OpenTelemetry> otel,
      @Value("${travelos.temporal.address:localhost:7233}") String address,
      @Value("${travelos.temporal.namespace:travelos}") String namespace) {
    WorkflowServiceStubs stubs =
        WorkflowServiceStubs.newServiceStubs(
            WorkflowServiceStubsOptions.newBuilder().setTarget(address).build());
    OpenTracingOptions tracing =
        OpenTracingOptions.newBuilder()
            .setTracer(
                OpenTracingShim.createTracerShim(otel.getIfAvailable(() -> OpenTelemetry.noop())))
            .build();
    return WorkflowClient.newInstance(
        stubs,
        WorkflowClientOptions.newBuilder()
            .setNamespace(namespace)
            .setInterceptors(new OpenTracingClientInterceptor(tracing))
            .build());
  }

  @Bean
  @ConditionalOnMissingBean(RecoverySignaler.class)
  @ConditionalOnProperty(
      prefix = "travelos.temporal",
      name = "enabled",
      havingValue = "true",
      matchIfMissing = true)
  RecoverySignaler temporalRecoverySignaler(WorkflowClient client) {
    return (disruptionId, decision) -> {
      try {
        client
            .newUntypedWorkflowStub(DisruptionRecovery.workflowId(disruptionId))
            .signal(DisruptionRecovery.SIGNAL_APPROVAL_DECIDED, decision);
      } catch (WorkflowNotFoundException e) {
        log.warn(
            "no running recovery for {}; approval {} recorded without signal",
            disruptionId,
            decision.approvalId());
      }
    };
  }

  @Bean
  @ConditionalOnMissingBean(RecoverySignaler.class)
  @ConditionalOnProperty(prefix = "travelos.temporal", name = "enabled", havingValue = "false")
  RecoverySignaler loggingRecoverySignaler() {
    return (disruptionId, decision) ->
        log.info(
            "temporal disabled: approval {} for {} not signalled",
            decision.approvalId(),
            disruptionId);
  }
}
