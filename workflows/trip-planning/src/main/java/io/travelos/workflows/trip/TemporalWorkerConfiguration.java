package io.travelos.workflows.trip;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.opentracingshim.OpenTracingShim;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.opentracing.OpenTracingClientInterceptor;
import io.temporal.opentracing.OpenTracingOptions;
import io.temporal.opentracing.OpenTracingWorkerInterceptor;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import io.temporal.worker.WorkerFactoryOptions;
import io.travelos.workflows.TripPlanning;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Plain Temporal SDK wiring: one worker on the trip-planning task queue. */
@Configuration(proxyBeanMethods = false)
class TemporalWorkerConfiguration {

  private static final Logger log = LoggerFactory.getLogger(TemporalWorkerConfiguration.class);

  @Bean
  WorkflowServiceStubs workflowServiceStubs(
      @Value("${travelos.temporal.address:localhost:7233}") String address) {
    return WorkflowServiceStubs.newServiceStubs(
        WorkflowServiceStubsOptions.newBuilder().setTarget(address).build());
  }

  /**
   * Temporal propagates a span context in workflow headers: the Kafka record that starts the
   * workflow becomes the parent of the workflow span, and every activity runs inside its own child
   * span, so the gRPC calls made from activities continue the same trace.
   */
  @Bean
  OpenTracingOptions openTracingOptions(ObjectProvider<OpenTelemetry> otel) {
    OpenTelemetry telemetry = otel.getIfAvailable(() -> OpenTelemetry.noop());
    return OpenTracingOptions.newBuilder()
        .setTracer(OpenTracingShim.createTracerShim(telemetry))
        .build();
  }

  @Bean
  WorkflowClient workflowClient(
      WorkflowServiceStubs stubs,
      OpenTracingOptions tracing,
      @Value("${travelos.temporal.namespace:travelos}") String namespace) {
    return WorkflowClient.newInstance(
        stubs,
        WorkflowClientOptions.newBuilder()
            .setNamespace(namespace)
            .setInterceptors(new OpenTracingClientInterceptor(tracing))
            .build());
  }

  @Bean
  WorkerFactory workerFactory(
      WorkflowClient client,
      TripActivities activities,
      WorkflowProperties properties,
      OpenTracingOptions tracing) {
    // The workflow reads the payment token through a side effect from a system property so the
    // deterministic code never touches Spring.
    System.setProperty("travelos.workflow.payment-token", properties.paymentToken());
    System.setProperty("travelos.workflow.default-timezone", properties.defaultTimezone());
    WorkerFactory factory =
        WorkerFactory.newInstance(
            client,
            WorkerFactoryOptions.newBuilder()
                .setWorkerInterceptors(new OpenTracingWorkerInterceptor(tracing))
                .build());
    Worker worker = factory.newWorker(TripPlanning.TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(TripWorkflowImpl.class);
    worker.registerActivitiesImplementations(activities);
    return factory;
  }

  @Bean
  SmartLifecycle workerLifecycle(WorkerFactory factory) {
    return new SmartLifecycle() {
      private boolean running;

      @Override
      public void start() {
        factory.start();
        running = true;
        log.info("Temporal worker polling task queue {}", TripPlanning.TASK_QUEUE);
      }

      @Override
      public void stop() {
        factory.shutdown();
        running = false;
      }

      @Override
      public boolean isRunning() {
        return running;
      }
    };
  }
}
