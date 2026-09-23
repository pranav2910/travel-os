package io.travelos.workflows.trip;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.workflow.Workflow;
import io.travelos.contracts.common.v1.Principal;
import io.travelos.contracts.common.v1.RequestContext;
import io.travelos.contracts.order.v1.CancelOrderCommand;
import io.travelos.contracts.order.v1.GetOrderRequest;
import io.travelos.contracts.order.v1.Order;
import io.travelos.contracts.order.v1.OrderItem;
import io.travelos.contracts.order.v1.OrderItemStatus;
import io.travelos.contracts.order.v1.OrderStatus;
import io.travelos.contracts.trip.v1.TransitionTripRequest;
import io.travelos.contracts.trip.v1.Trip;
import io.travelos.contracts.trip.v1.TripStatus;
import io.travelos.workflows.TripCancellation;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;

/**
 * Releases a booked trip's reservation and reports the truth back to Travel Core:
 *
 * <ol>
 *   <li>ask the Order service to cancel the order; it is idempotent by state and resumes from the
 *       item states, so retries after lost answers or a worker restart never release anything twice
 *       or refund twice
 *   <li>when the order is CANCELLED, the trip becomes CANCELLED (and only then)
 *   <li>when a supplier refused, the trip stays CANCELLING with CANCELLATION_INCOMPLETE and the
 *       workflow waits (30 s doubling to hourly, for up to 30 days) for a person to resolve the
 *       exposure; the order's CANCELLED then completes the trip
 * </ol>
 *
 * <p>The Order service being away is not a refusal: the cancel activity retries for a day before
 * the trip is left CANCELLING with ORDER_UNREACHABLE for a person.
 */
public class TripCancellationWorkflowImpl implements TripCancellationWorkflow {

  private static final Logger log = Workflow.getLogger(TripCancellationWorkflowImpl.class);

  static final String PRINCIPAL = "agent/trip-cancellation/v1";
  static final Duration FIRST_POLL = Duration.ofSeconds(30);
  static final Duration MAX_POLL = Duration.ofHours(1);
  static final Duration MAX_WAIT = Duration.ofDays(30);
  static final Duration RELEASE_DEADLINE = Duration.ofHours(24);

  private final TripActivities travelCore =
      Workflow.newActivityStub(
          TripActivities.class,
          ActivityOptions.newBuilder()
              .setStartToCloseTimeout(Duration.ofSeconds(30))
              .setRetryOptions(
                  RetryOptions.newBuilder()
                      .setInitialInterval(Duration.ofSeconds(1))
                      .setMaximumInterval(Duration.ofMinutes(1))
                      .setMaximumAttempts(20)
                      .build())
              .build());

  private final TripActivities releasing =
      Workflow.newActivityStub(
          TripActivities.class,
          ActivityOptions.newBuilder()
              .setStartToCloseTimeout(Duration.ofMinutes(5))
              .setScheduleToCloseTimeout(RELEASE_DEADLINE)
              .setRetryOptions(
                  RetryOptions.newBuilder()
                      .setInitialInterval(Duration.ofSeconds(5))
                      .setBackoffCoefficient(2.0)
                      .setMaximumInterval(Duration.ofMinutes(10))
                      .build())
              .build());

  private TripCancellation.Stage stage = TripCancellation.Stage.LOADING;

  @Override
  public Outcome run(TripCancellation.Input input) {
    String tenant = input.tenantId();
    String tripId = input.tripId();
    Trip trip = travelCore.loadTrip(tenant, tripId);
    if (trip.getStatus() == TripStatus.CANCELLED) {
      stage = TripCancellation.Stage.CANCELLED;
      return new Outcome(tripId, "CANCELLED", null);
    }
    if (trip.getStatus() != TripStatus.CANCELLING) {
      log.warn("trip {} is {}, not CANCELLING; nothing to release", tripId, trip.getStatus());
      stage = TripCancellation.Stage.NOTHING_TO_DO;
      return new Outcome(tripId, trip.getStatus().name(), null);
    }
    String orderId = input.orderId().isBlank() ? trip.getOrderId() : input.orderId();
    String reason = input.reason().isBlank() ? "cancelled" : input.reason();

    stage = TripCancellation.Stage.RELEASING;
    Order order;
    try {
      order = cancelOrder(tenant, tripId, orderId, reason);
    } catch (ActivityFailure e) {
      String code = FailureCodes.of(e);
      String message = FailureCodes.message(e);
      log.error("trip {}: order {} could not be released: {} {}", tripId, orderId, code, message);
      incomplete(
          tenant,
          tripId,
          "ORDER_" + (code.startsWith("ACTIVITY_") ? code.substring("ACTIVITY_".length()) : code),
          "order " + orderId + " could not be released: " + message);
      stage = TripCancellation.Stage.INCOMPLETE;
      return new Outcome(tripId, "CANCELLING", code);
    }

    Duration waited = Duration.ZERO;
    Duration interval = FIRST_POLL;
    boolean reported = false;
    while (order.getStatus() != OrderStatus.CANCELLED) {
      if (isIncomplete(order)) {
        if (!reported) {
          incomplete(tenant, tripId, "CANCELLATION_INCOMPLETE", describe(order));
          reported = true;
        }
        stage = TripCancellation.Stage.AWAITING_RESOLUTION;
      }
      if (waited.compareTo(MAX_WAIT) >= 0) {
        incomplete(
            tenant,
            tripId,
            "CANCELLATION_UNRESOLVED",
            "order " + orderId + " was not fully released within " + MAX_WAIT.toDays() + " days");
        stage = TripCancellation.Stage.INCOMPLETE;
        return new Outcome(tripId, "CANCELLING", "CANCELLATION_UNRESOLVED");
      }
      Workflow.sleep(interval);
      waited = waited.plus(interval);
      interval =
          interval.multipliedBy(2).compareTo(MAX_POLL) > 0 ? MAX_POLL : interval.multipliedBy(2);
      if (isIncomplete(order)) {
        // a person is on it: look, do not ask the supplier again
        order =
            travelCore.getOrder(
                GetOrderRequest.newBuilder()
                    .setCtx(ctx(tenant, tripId, ""))
                    .setOrderId(orderId)
                    .build());
      } else {
        // still pending without a refusal (an answer was lost): the cancel resumes where it was
        try {
          order = cancelOrder(tenant, tripId, orderId, reason);
        } catch (ActivityFailure e) {
          incomplete(tenant, tripId, "ORDER_UNREACHABLE", FailureCodes.message(e));
          stage = TripCancellation.Stage.INCOMPLETE;
          return new Outcome(tripId, "CANCELLING", "ORDER_UNREACHABLE");
        }
      }
    }

    travelCore.transition(
        TransitionTripRequest.newBuilder()
            .setCtx(ctx(tenant, tripId, ""))
            .setTripId(tripId)
            .setTo(TripStatus.CANCELLED)
            .setOrderId(orderId)
            .setReason(
                reported
                    ? "every component released; the refused ones were resolved by a person"
                    : "every component released at the suppliers")
            .build());
    stage = TripCancellation.Stage.CANCELLED;
    return new Outcome(tripId, "CANCELLED", null);
  }

  @Override
  public TripCancellation.Stage stage() {
    return stage;
  }

  private Order cancelOrder(String tenant, String tripId, String orderId, String reason) {
    return releasing.cancelOrder(
        CancelOrderCommand.newBuilder()
            .setCtx(ctx(tenant, tripId, tripId + ":CANCEL-ORDER:1"))
            .setOrderId(orderId)
            .setReason(reason)
            .build());
  }

  /** The trip stays CANCELLING; Travel Core records the code once and tells the people involved. */
  private void incomplete(String tenant, String tripId, String code, String message) {
    try {
      travelCore.transition(
          TransitionTripRequest.newBuilder()
              .setCtx(ctx(tenant, tripId, ""))
              .setTripId(tripId)
              .setTo(TripStatus.CANCELLING)
              .setFailureStage("CANCELLATION")
              .setFailureCode(code)
              .setReason(message)
              .build());
    } catch (ActivityFailure e) {
      log.error("trip {}: could not record {} ({})", tripId, code, e.getMessage());
    }
  }

  static boolean isIncomplete(Order order) {
    return "CANCELLATION_INCOMPLETE".equals(order.getFailureCode())
        || order.getItemsList().stream()
            .anyMatch(i -> i.getStatus() == OrderItemStatus.ITEM_CANCEL_FAILED);
  }

  private static String describe(Order order) {
    List<String> refused =
        order.getItemsList().stream()
            .filter(i -> i.getStatus() == OrderItemStatus.ITEM_CANCEL_FAILED)
            .map(TripCancellationWorkflowImpl::describe)
            .toList();
    return "the supplier refused to release "
        + (refused.isEmpty() ? "part of the reservation" : String.join(", ", refused))
        + "; a person is resolving the exposure and the reservation stays confirmed until then";
  }

  private static String describe(OrderItem item) {
    String kind =
        item.hasOffer()
            ? item.getOffer().getType().name().toLowerCase(java.util.Locale.ROOT)
            : "component";
    String provider = item.hasOffer() ? item.getOffer().getProvider() : "";
    return (kind + " " + provider).trim()
        + (item.getExternalRef().isBlank() ? "" : " " + item.getExternalRef())
        + (item.getComponentId().isBlank() ? "" : " (" + item.getComponentId() + ")");
  }

  private static RequestContext ctx(String tenant, String tripId, String idempotencyKey) {
    return RequestContext.newBuilder()
        .setTenantId(tenant)
        .setCorrelationId(tripId)
        .setCausationId(Workflow.getInfo().getWorkflowId())
        .setIdempotencyKey(idempotencyKey)
        .setPrincipal(Principal.newBuilder().setKind(Principal.Kind.AGENT).setId(PRINCIPAL))
        .build();
  }
}
