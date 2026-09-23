package io.travelos.workflows.trip;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.Status;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import io.travelos.contracts.offer.v1.Offer;
import io.travelos.contracts.offer.v1.OfferType;
import io.travelos.contracts.order.v1.Order;
import io.travelos.contracts.order.v1.OrderItem;
import io.travelos.contracts.order.v1.OrderItemStatus;
import io.travelos.contracts.order.v1.OrderStatus;
import io.travelos.contracts.trip.v1.TransitionTripRequest;
import io.travelos.contracts.trip.v1.Trip;
import io.travelos.contracts.trip.v1.TripStatus;
import io.travelos.workflows.TripCancellation;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * BUG-08: the release of a booked trip's reservation, on Temporal's time-skipping test server with
 * scripted activities. The trip is CANCELLED only once the order is; a refusal leaves it CANCELLING
 * with a reason; lost answers are retried without releasing anything twice.
 */
class TripCancellationWorkflowTest {

  private static final String TENANT = "acme";
  private static final String TRIP = "trip_01ARZ3NDEKTSV4RRFFQ69G5FC1";
  private static final String ORDER = "ord_01ARZ3NDEKTSV4RRFFQ69G5FC1";

  private TestWorkflowEnvironment env;
  private TripActivities activities;
  private WorkflowClient client;
  private final List<TransitionTripRequest> transitions = new CopyOnWriteArrayList<>();

  @BeforeEach
  void setUp() {
    env =
        TestWorkflowEnvironment.newInstance(
            TestEnvironmentOptions.newBuilder()
                .setInitialTime(Instant.parse("2026-09-23T10:00:00Z"))
                .build());
    Worker worker = env.newWorker(TripCancellation.TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(TripCancellationWorkflowImpl.class);
    activities = mock(TripActivities.class);
    worker.registerActivitiesImplementations(activities);
    env.start();
    client = env.getWorkflowClient();
    when(activities.loadTrip(anyString(), anyString())).thenReturn(trip(TripStatus.CANCELLING));
    when(activities.transition(any()))
        .thenAnswer(
            inv -> {
              TransitionTripRequest r = inv.getArgument(0);
              transitions.add(r);
              return trip(r.getTo()).toBuilder().setFailureCode(r.getFailureCode()).build();
            });
  }

  @AfterEach
  void tearDown() {
    env.close();
  }

  @Test
  void aReleasedOrderCancelsTheTrip() {
    when(activities.cancelOrder(any())).thenReturn(order(OrderStatus.CANCELLED, ""));
    TripCancellationWorkflow.Outcome outcome = result(start());
    assertThat(outcome.finalStatus()).isEqualTo("CANCELLED");
    assertThat(transitions).hasSize(1);
    assertThat(transitions.getFirst().getTo()).isEqualTo(TripStatus.CANCELLED);
    assertThat(transitions.getFirst().getOrderId()).isEqualTo(ORDER);
    assertThat(transitions.getFirst().getReason()).contains("released");
    verify(activities, times(1)).cancelOrder(any());
    verify(activities, never()).getOrder(any());
  }

  @Test
  void aRefusalKeepsTheTripCancellingUntilAPersonResolvesTheExposure() {
    when(activities.cancelOrder(any())).thenReturn(refused());
    when(activities.getOrder(any()))
        .thenReturn(refused())
        .thenReturn(refused())
        .thenReturn(order(OrderStatus.CANCELLED, ""));
    TripCancellationWorkflow.Outcome outcome = result(start());
    assertThat(outcome.finalStatus()).isEqualTo("CANCELLED");
    assertThat(transitions).hasSize(2);
    assertThat(transitions.get(0).getTo()).isEqualTo(TripStatus.CANCELLING);
    assertThat(transitions.get(0).getFailureCode()).isEqualTo("CANCELLATION_INCOMPLETE");
    assertThat(transitions.get(0).getReason())
        .contains("refused")
        .contains("hotel sandbox-hotel HTL-1")
        .contains("stays confirmed");
    assertThat(transitions.get(1).getTo()).isEqualTo(TripStatus.CANCELLED);
    assertThat(transitions.get(1).getReason()).contains("resolved by a person");
    verify(activities, times(1)).cancelOrder(any());
    verify(activities, times(3)).getOrder(any());
  }

  @Test
  void aLostAnswerIsRetriedWithTheSameKeyAndNothingIsReleasedTwice() {
    when(activities.cancelOrder(any()))
        .thenThrow(
            Status.UNAVAILABLE.withDescription("order service restarting").asRuntimeException())
        .thenThrow(Status.DEADLINE_EXCEEDED.withDescription("lost").asRuntimeException())
        .thenReturn(order(OrderStatus.CANCELLED, ""));
    TripCancellationWorkflow.Outcome outcome = result(start());
    assertThat(outcome.finalStatus()).isEqualTo("CANCELLED");
    verify(activities, times(3)).cancelOrder(any());
    assertThat(transitions)
        .extracting(TransitionTripRequest::getTo)
        .containsExactly(TripStatus.CANCELLED);
  }

  @Test
  void aPendingOrderWithoutARefusalIsAskedAgainNotJustWatched() {
    // the order service answered while still mid-release (no refusal): the cancel is resumed
    when(activities.cancelOrder(any()))
        .thenReturn(order(OrderStatus.CANCELLATION_PENDING, ""))
        .thenReturn(order(OrderStatus.CANCELLED, ""));
    TripCancellationWorkflow.Outcome outcome = result(start());
    assertThat(outcome.finalStatus()).isEqualTo("CANCELLED");
    verify(activities, times(2)).cancelOrder(any());
    verify(activities, never()).getOrder(any());
    assertThat(transitions)
        .extracting(TransitionTripRequest::getTo)
        .containsExactly(TripStatus.CANCELLED);
  }

  @Test
  void anOrderThatCannotBeReleasedLeavesTheTripCancellingForAPerson() {
    when(activities.cancelOrder(any()))
        .thenThrow(
            ApplicationFailure.newNonRetryableFailure(
                "FAILED_PRECONDITION: ORDER_NOT_CANCELLABLE: status FAILED",
                "FAILED_PRECONDITION"));
    TripCancellationWorkflow.Outcome outcome = result(start());
    assertThat(outcome.finalStatus()).isEqualTo("CANCELLING");
    assertThat(transitions).hasSize(1);
    assertThat(transitions.getFirst().getTo()).isEqualTo(TripStatus.CANCELLING);
    assertThat(transitions.getFirst().getFailureCode()).isEqualTo("ORDER_FAILED_PRECONDITION");
    assertThat(transitions.getFirst().getReason()).contains("ORDER_NOT_CANCELLABLE");
  }

  @Test
  void aTripThatIsNotCancellingIsLeftAlone() {
    when(activities.loadTrip(anyString(), anyString())).thenReturn(trip(TripStatus.BOOKED));
    TripCancellationWorkflow.Outcome outcome = result(start());
    assertThat(outcome.finalStatus()).isEqualTo("BOOKED");
    verify(activities, never()).cancelOrder(any());
    assertThat(transitions).isEmpty();
  }

  @Test
  void waitingForAPersonGivesUpAfterThirtyDaysWithoutPretendingItIsCancelled() {
    when(activities.cancelOrder(any())).thenReturn(refused());
    when(activities.getOrder(any())).thenReturn(refused());
    TripCancellationWorkflow.Outcome outcome = result(start());
    assertThat(outcome.finalStatus()).isEqualTo("CANCELLING");
    assertThat(outcome.failureCode()).isEqualTo("CANCELLATION_UNRESOLVED");
    assertThat(transitions)
        .extracting(TransitionTripRequest::getFailureCode)
        .containsExactly("CANCELLATION_INCOMPLETE", "CANCELLATION_UNRESOLVED");
    assertThat(transitions)
        .extracting(TransitionTripRequest::getTo)
        .doesNotContain(TripStatus.CANCELLED);
    verify(activities, times(1)).cancelOrder(any());
  }

  // ------------------------------------------------------------------ helpers

  private TripCancellationWorkflow start() {
    TripCancellationWorkflow workflow =
        client.newWorkflowStub(
            TripCancellationWorkflow.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(TripCancellation.TASK_QUEUE)
                .setWorkflowId(TripCancellation.workflowId(TRIP))
                .build());
    WorkflowClient.start(
        workflow::run,
        new TripCancellation.Input(TENANT, TRIP, ORDER, "Meeting moved to video", "human/alice"));
    return workflow;
  }

  private static TripCancellationWorkflow.Outcome result(TripCancellationWorkflow workflow) {
    return WorkflowStub.fromTyped(workflow).getResult(TripCancellationWorkflow.Outcome.class);
  }

  private static Trip trip(TripStatus status) {
    return Trip.newBuilder()
        .setTripId(TRIP)
        .setTenantId(TENANT)
        .setTravelerId("emp_1001")
        .setStatus(status)
        .setOrderId(ORDER)
        .build();
  }

  private static Order order(OrderStatus status, String failureCode) {
    return Order.newBuilder()
        .setOrderId(ORDER)
        .setTripId(TRIP)
        .setStatus(status)
        .setFailureCode(failureCode)
        .build();
  }

  private static Order refused() {
    return order(OrderStatus.CANCELLATION_PENDING, "CANCELLATION_INCOMPLETE").toBuilder()
        .addItems(
            OrderItem.newBuilder()
                .setItemId("itm_air")
                .setStatus(OrderItemStatus.ITEM_CANCELLED)
                .setOffer(Offer.newBuilder().setType(OfferType.AIR).setProvider("sandbox-air")))
        .addItems(
            OrderItem.newBuilder()
                .setItemId("itm_hotel")
                .setStatus(OrderItemStatus.ITEM_CANCEL_FAILED)
                .setExternalRef("HTL-1")
                .setOffer(Offer.newBuilder().setType(OfferType.HOTEL).setProvider("sandbox-hotel")))
        .build();
  }
}
