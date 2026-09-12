package io.travelos.workflows.trip;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import io.travelos.workflows.TripPlanning;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

  @Bean
  WorkflowClient workflowClient(
      WorkflowServiceStubs stubs,
      @Value("${travelos.temporal.namespace:travelos}") String namespace) {
    return WorkflowClient.newInstance(
        stubs, WorkflowClientOptions.newBuilder().setNamespace(namespace).build());
  }

  @Bean
  WorkerFactory workerFactory(
      WorkflowClient client, TripActivities activities, WorkflowProperties properties) {
    // The workflow reads the payment token through a side effect from a system property so the
    // deterministic code never touches Spring.
    System.setProperty("travelos.workflow.payment-token", properties.paymentToken());
    System.setProperty("travelos.workflow.default-timezone", properties.defaultTimezone());
    WorkerFactory factory = WorkerFactory.newInstance(client);
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
