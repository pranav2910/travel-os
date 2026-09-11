package io.travelos.workflows.trip;

import io.temporal.activity.ActivityInterface;
import io.travelos.contracts.optimization.v1.OptimizeTripRequest;
import io.travelos.contracts.optimization.v1.OptimizeTripResponse;
import io.travelos.contracts.order.v1.CreateOrderCommand;
import io.travelos.contracts.order.v1.Order;
import io.travelos.contracts.policy.v1.EvaluateTripRequest;
import io.travelos.contracts.policy.v1.EvaluateTripResponse;
import io.travelos.contracts.supplier.v1.SearchAirRequest;
import io.travelos.contracts.supplier.v1.SearchAirResponse;
import io.travelos.contracts.trip.v1.TransitionTripRequest;
import io.travelos.contracts.trip.v1.Trip;

/**
 * Every side effect of the workflow, one gRPC call each. Activities are retried by Temporal on
 * retryable statuses and fail fast on the rest (see {@link TripActivitiesImpl}). All of them are
 * safe to retry: reads are pure, transitions are idempotent by state, CreateOrder by key.
 */
@ActivityInterface
public interface TripActivities {

  Trip loadTrip(String tenantId, String tripId);

  Trip transition(TransitionTripRequest request);

  SearchAirResponse search(SearchAirRequest request);

  EvaluateTripResponse evaluatePolicy(EvaluateTripRequest request);

  OptimizeTripResponse optimize(OptimizeTripRequest request);

  Order createOrder(CreateOrderCommand command);
}
