package io.travelos.workflows.recovery;

import io.temporal.activity.ActivityInterface;
import io.travelos.contracts.disruption.v1.Disruption;
import io.travelos.contracts.disruption.v1.RecordRecoveryDecisionRequest;
import io.travelos.contracts.disruption.v1.RecordRecoveryOutcomeRequest;
import io.travelos.contracts.disruption.v1.TransitionDisruptionRequest;
import io.travelos.contracts.llm.v1.ExplainDisruptionRequest;
import io.travelos.contracts.llm.v1.ExplainDisruptionResponse;
import io.travelos.contracts.optimization.v1.OptimizeTripRequest;
import io.travelos.contracts.optimization.v1.OptimizeTripResponse;
import io.travelos.contracts.order.v1.ChangeOrderCommand;
import io.travelos.contracts.order.v1.Order;
import io.travelos.contracts.policy.v1.EvaluateActionRequest;
import io.travelos.contracts.policy.v1.EvaluateTripRequest;
import io.travelos.contracts.policy.v1.EvaluateTripResponse;
import io.travelos.contracts.policy.v1.PolicyDecision;
import io.travelos.contracts.supplier.v1.SearchAirRequest;
import io.travelos.contracts.supplier.v1.SearchAirResponse;
import io.travelos.contracts.trip.v1.Trip;

/**
 * Every side effect of the recovery workflow, one gRPC call each, all safe to retry: reads are
 * pure, transitions and records are idempotent by state, ChangeOrder by key.
 */
@ActivityInterface
public interface RecoveryActivities {

  Disruption loadDisruption(String tenantId, String disruptionId);

  Order loadOrder(String tenantId, String correlationId, String orderId);

  Trip loadTrip(String tenantId, String tripId);

  Disruption transition(TransitionDisruptionRequest request);

  SearchAirResponse search(SearchAirRequest request);

  EvaluateTripResponse evaluatePolicy(EvaluateTripRequest request);

  OptimizeTripResponse optimize(OptimizeTripRequest request);

  /** Policy decides whether the agent may change the order: ALLOW, ALLOW_WITH_APPROVAL or DENY. */
  PolicyDecision evaluateAction(EvaluateActionRequest request);

  Disruption recordDecision(RecordRecoveryDecisionRequest request);

  /** LLM gateway narration. Optional: failure never blocks a recovery. */
  ExplainDisruptionResponse explain(ExplainDisruptionRequest request);

  Order changeOrder(ChangeOrderCommand command);

  Disruption recordOutcome(RecordRecoveryOutcomeRequest request);
}
