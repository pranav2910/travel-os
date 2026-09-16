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
import io.travelos.workflows.DemandSync;
import io.travelos.workflows.DisruptionRecovery;
import io.travelos.workflows.LearningBuild;
import io.travelos.workflows.TripPlanning;
import io.travelos.workflows.demand.DemandActivities;
import io.travelos.workflows.demand.DemandSyncWorkflowImpl;
import io.travelos.workflows.learning.LearningActivities;
import io.travelos.workflows.learning.LearningBuildWorkflowImpl;
import io.travelos.workflows.recovery.RecoveryActivities;
import io.travelos.workflows.recovery.RecoveryWorkflowImpl;
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
      RecoveryActivities recoveryActivities,
      DemandActivities demandActivities,
      LearningActivities learningActivities,
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
    // Slice 5: planning resolves its learning inputs through one pinned activity.
    worker.registerActivitiesImplementations(activities, learningActivities);
    // Slice 2: disruption recovery runs on its own task queue so its load never starves planning.
    Worker recovery = factory.newWorker(DisruptionRecovery.TASK_QUEUE);
    recovery.registerWorkflowImplementationTypes(RecoveryWorkflowImpl.class);
    recovery.registerActivitiesImplementations(recoveryActivities, learningActivities);
    // Slice 4: connector synchronization runs on its own queue; a slow source never blocks a trip.
    Worker demand = factory.newWorker(DemandSync.TASK_QUEUE);
    demand.registerWorkflowImplementationTypes(DemandSyncWorkflowImpl.class);
    demand.registerActivitiesImplementations(demandActivities);
    // Slice 5: profile builds run on their own queue; a slow build never blocks a trip.
    Worker learning = factory.newWorker(LearningBuild.TASK_QUEUE);
    learning.registerWorkflowImplementationTypes(LearningBuildWorkflowImpl.class);
    learning.registerActivitiesImplementations(learningActivities);
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
