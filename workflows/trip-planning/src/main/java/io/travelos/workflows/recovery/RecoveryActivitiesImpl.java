package io.travelos.workflows.recovery;

import io.grpc.StatusRuntimeException;
import io.temporal.failure.ApplicationFailure;
import io.travelos.contracts.common.v1.Principal;
import io.travelos.contracts.common.v1.RequestContext;
import io.travelos.contracts.disruption.v1.Disruption;
import io.travelos.contracts.disruption.v1.DisruptionServiceGrpc;
import io.travelos.contracts.disruption.v1.GetDisruptionRequest;
import io.travelos.contracts.disruption.v1.RecordRecoveryDecisionRequest;
import io.travelos.contracts.disruption.v1.RecordRecoveryOutcomeRequest;
import io.travelos.contracts.disruption.v1.TransitionDisruptionRequest;
import io.travelos.contracts.llm.v1.ExplainDisruptionRequest;
import io.travelos.contracts.llm.v1.ExplainDisruptionResponse;
import io.travelos.contracts.llm.v1.LlmGatewayGrpc;
import io.travelos.contracts.optimization.v1.OptimizationServiceGrpc;
import io.travelos.contracts.optimization.v1.OptimizeTripRequest;
import io.travelos.contracts.optimization.v1.OptimizeTripResponse;
import io.travelos.contracts.order.v1.ChangeOrderCommand;
import io.travelos.contracts.order.v1.GetOrderRequest;
import io.travelos.contracts.order.v1.Order;
import io.travelos.contracts.order.v1.OrderServiceGrpc;
import io.travelos.contracts.policy.v1.EvaluateActionRequest;
import io.travelos.contracts.policy.v1.EvaluateTripRequest;
import io.travelos.contracts.policy.v1.EvaluateTripResponse;
import io.travelos.contracts.policy.v1.PolicyDecision;
import io.travelos.contracts.policy.v1.PolicyServiceGrpc;
import io.travelos.contracts.supplier.v1.SearchAirRequest;
import io.travelos.contracts.supplier.v1.SearchAirResponse;
import io.travelos.contracts.supplier.v1.SupplierGatewayGrpc;
import io.travelos.contracts.trip.v1.GetTripRequest;
import io.travelos.contracts.trip.v1.TravelCoreServiceGrpc;
import io.travelos.contracts.trip.v1.Trip;
import io.travelos.spring.grpc.GrpcChannels;
import io.travelos.workflows.DisruptionRecovery;
import io.travelos.workflows.trip.TripActivitiesImpl;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/** gRPC adapters for the recovery workflow. A final status becomes a non-retryable failure. */
@Component
public class RecoveryActivitiesImpl implements RecoveryActivities {

  private final GrpcChannels channels;

  public RecoveryActivitiesImpl(GrpcChannels channels) {
    this.channels = channels;
  }

  @Override
  public Disruption loadDisruption(String tenantId, String disruptionId) {
    return call(
        () ->
            stub(DisruptionServiceGrpc::newBlockingStub, "disruption")
                .getDisruption(
                    GetDisruptionRequest.newBuilder()
                        .setCtx(ctx(tenantId, disruptionId, ""))
                        .setDisruptionId(disruptionId)
                        .build()));
  }

  @Override
  public Order loadOrder(String tenantId, String correlationId, String orderId) {
    return call(
        () ->
            stub(OrderServiceGrpc::newBlockingStub, "order")
                .getOrder(
                    GetOrderRequest.newBuilder()
                        .setCtx(ctx(tenantId, correlationId, ""))
                        .setOrderId(orderId)
                        .build()));
  }

  @Override
  public Trip loadTrip(String tenantId, String tripId) {
    return call(
        () ->
            stub(TravelCoreServiceGrpc::newBlockingStub, "travel-core")
                .getTrip(
                    GetTripRequest.newBuilder()
                        .setCtx(ctx(tenantId, tripId, ""))
                        .setTripId(tripId)
                        .build()));
  }

  @Override
  public Disruption transition(TransitionDisruptionRequest request) {
    return call(
        () ->
            stub(DisruptionServiceGrpc::newBlockingStub, "disruption")
                .transitionDisruption(request));
  }

  @Override
  public SearchAirResponse search(SearchAirRequest request) {
    return call(
        () -> stub(SupplierGatewayGrpc::newBlockingStub, "supplier-gateway").searchAir(request));
  }

  @Override
  public EvaluateTripResponse evaluatePolicy(EvaluateTripRequest request) {
    return call(() -> stub(PolicyServiceGrpc::newBlockingStub, "policy").evaluateTrip(request));
  }

  @Override
  public OptimizeTripResponse optimize(OptimizeTripRequest request) {
    return call(
        () -> stub(OptimizationServiceGrpc::newBlockingStub, "optimization").optimizeTrip(request));
  }

  @Override
  public PolicyDecision evaluateAction(EvaluateActionRequest request) {
    return call(() -> stub(PolicyServiceGrpc::newBlockingStub, "policy").evaluateAction(request));
  }

  @Override
  public Disruption recordDecision(RecordRecoveryDecisionRequest request) {
    return call(
        () ->
            stub(DisruptionServiceGrpc::newBlockingStub, "disruption")
                .recordRecoveryDecision(request));
  }

  @Override
  public ExplainDisruptionResponse explain(ExplainDisruptionRequest request) {
    return call(
        () -> stub(LlmGatewayGrpc::newBlockingStub, "llm-gateway").explainDisruption(request));
  }

  @Override
  public Order changeOrder(ChangeOrderCommand command) {
    return call(() -> stub(OrderServiceGrpc::newBlockingStub, "order").changeOrder(command));
  }

  @Override
  public Disruption recordOutcome(RecordRecoveryOutcomeRequest request) {
    return call(
        () ->
            stub(DisruptionServiceGrpc::newBlockingStub, "disruption")
                .recordRecoveryOutcome(request));
  }

  static RequestContext ctx(String tenantId, String correlationId, String idempotencyKey) {
    return RequestContext.newBuilder()
        .setTenantId(tenantId)
        .setCorrelationId(correlationId)
        .setIdempotencyKey(idempotencyKey)
        .setPrincipal(
            Principal.newBuilder().setKind(Principal.Kind.AGENT).setId(DisruptionRecovery.AGENT))
        .build();
  }

  private <S extends io.grpc.stub.AbstractBlockingStub<S>> S stub(
      java.util.function.Function<io.grpc.Channel, S> factory, String name) {
    return factory
        .apply(channels.channel(name))
        .withDeadlineAfter(channels.deadline(name).toMillis(), TimeUnit.MILLISECONDS);
  }

  private static <T> T call(Supplier<T> call) {
    try {
      return call.get();
    } catch (StatusRuntimeException e) {
      if (TripActivitiesImpl.isFinal(e.getStatus())) {
        throw ApplicationFailure.newNonRetryableFailure(
            e.getStatus().getCode() + ": " + e.getStatus().getDescription(),
            e.getStatus().getCode().name());
      }
      throw e;
    }
  }
}
