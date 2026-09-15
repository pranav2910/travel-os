package io.travelos.workflows.trip;

import io.temporal.activity.ActivityInterface;
import io.travelos.contracts.llm.v1.ExplainTripRequest;
import io.travelos.contracts.llm.v1.ExplainTripResponse;
import io.travelos.contracts.llm.v1.ExtractIntentRequest;
import io.travelos.contracts.llm.v1.ExtractIntentResponse;
import io.travelos.contracts.optimization.v1.OptimizeItineraryRequest;
import io.travelos.contracts.optimization.v1.OptimizeItineraryResponse;
import io.travelos.contracts.optimization.v1.OptimizeTripRequest;
import io.travelos.contracts.optimization.v1.OptimizeTripResponse;
import io.travelos.contracts.order.v1.CreateOrderCommand;
import io.travelos.contracts.order.v1.Order;
import io.travelos.contracts.policy.v1.EvaluateTripRequest;
import io.travelos.contracts.policy.v1.EvaluateTripResponse;
import io.travelos.contracts.supplier.v1.QuoteOfferRequest;
import io.travelos.contracts.supplier.v1.QuoteOfferResponse;
import io.travelos.contracts.supplier.v1.SearchAirRequest;
import io.travelos.contracts.supplier.v1.SearchAirResponse;
import io.travelos.contracts.supplier.v1.SearchGroundRequest;
import io.travelos.contracts.supplier.v1.SearchGroundResponse;
import io.travelos.contracts.supplier.v1.SearchHotelsRequest;
import io.travelos.contracts.supplier.v1.SearchHotelsResponse;
import io.travelos.contracts.trip.v1.ApplyIntentExtractionRequest;
import io.travelos.contracts.trip.v1.TransitionTripRequest;
import io.travelos.contracts.trip.v1.Trip;
import io.travelos.contracts.trip.v1.UpdateComponentsRequest;

/**
 * Every side effect of the workflow, one gRPC call each. Activities are retried by Temporal on
 * retryable statuses and fail fast on the rest (see {@link TripActivitiesImpl}). All of them are
 * safe to retry: reads are pure, transitions are idempotent by state, CreateOrder by key, and
 * ApplyIntentExtraction by model-call id.
 */
@ActivityInterface
public interface TripActivities {

  Trip loadTrip(String tenantId, String tripId);

  Trip transition(TransitionTripRequest request);

  /** LLM gateway: free text to structured intent (or a question). */
  ExtractIntentResponse extractIntent(ExtractIntentRequest request);

  /** Travel Core: ledger the extraction and freeze the intent when there is one. */
  Trip applyIntentExtraction(ApplyIntentExtractionRequest request);

  SearchAirResponse search(SearchAirRequest request);

  EvaluateTripResponse evaluatePolicy(EvaluateTripRequest request);

  OptimizeTripResponse optimize(OptimizeTripRequest request);

  /** LLM gateway: narrate the decision from its evidence. Optional: failure never blocks a trip. */
  ExplainTripResponse explain(ExplainTripRequest request);

  Order createOrder(CreateOrderCommand command);

  // ---------------------------------------------------------------- Slice 3

  SearchHotelsResponse searchHotels(SearchHotelsRequest request);

  SearchGroundResponse searchGround(SearchGroundRequest request);

  OptimizeItineraryResponse optimizeItinerary(OptimizeItineraryRequest request);

  /** Revalidate one offer before a mutation (any kind). Pure. */
  QuoteOfferResponse quote(QuoteOfferRequest request);

  /** Report component states to Travel Core. Idempotent. */
  Trip updateComponents(UpdateComponentsRequest request);
}
