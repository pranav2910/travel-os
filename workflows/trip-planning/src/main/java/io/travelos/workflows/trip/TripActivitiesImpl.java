package io.travelos.workflows.trip;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.failure.ApplicationFailure;
import io.travelos.contracts.common.v1.Principal;
import io.travelos.contracts.common.v1.RequestContext;
import io.travelos.contracts.context.v1.EnterpriseContextServiceGrpc;
import io.travelos.contracts.context.v1.GetTravelerSnapshotRequest;
import io.travelos.contracts.context.v1.PassengerSnapshot;
import io.travelos.contracts.context.v1.TravelerSnapshotResponse;
import io.travelos.contracts.llm.v1.ExplainTripRequest;
import io.travelos.contracts.llm.v1.ExplainTripResponse;
import io.travelos.contracts.llm.v1.ExtractIntentRequest;
import io.travelos.contracts.llm.v1.ExtractIntentResponse;
import io.travelos.contracts.llm.v1.LlmGatewayGrpc;
import io.travelos.contracts.optimization.v1.OptimizationServiceGrpc;
import io.travelos.contracts.optimization.v1.OptimizeItineraryRequest;
import io.travelos.contracts.optimization.v1.OptimizeItineraryResponse;
import io.travelos.contracts.optimization.v1.OptimizeTripRequest;
import io.travelos.contracts.optimization.v1.OptimizeTripResponse;
import io.travelos.contracts.order.v1.CancelOrderCommand;
import io.travelos.contracts.order.v1.CreateOrderCommand;
import io.travelos.contracts.order.v1.GetOrderRequest;
import io.travelos.contracts.order.v1.Order;
import io.travelos.contracts.order.v1.OrderServiceGrpc;
import io.travelos.contracts.policy.v1.EvaluateTripRequest;
import io.travelos.contracts.policy.v1.EvaluateTripResponse;
import io.travelos.contracts.policy.v1.PolicyServiceGrpc;
import io.travelos.contracts.supplier.v1.Passenger;
import io.travelos.contracts.supplier.v1.PassengerDocument;
import io.travelos.contracts.supplier.v1.QuoteOfferRequest;
import io.travelos.contracts.supplier.v1.QuoteOfferResponse;
import io.travelos.contracts.supplier.v1.SearchAirRequest;
import io.travelos.contracts.supplier.v1.SearchAirResponse;
import io.travelos.contracts.supplier.v1.SearchGroundRequest;
import io.travelos.contracts.supplier.v1.SearchGroundResponse;
import io.travelos.contracts.supplier.v1.SearchHotelsRequest;
import io.travelos.contracts.supplier.v1.SearchHotelsResponse;
import io.travelos.contracts.supplier.v1.SupplierGatewayGrpc;
import io.travelos.contracts.trip.v1.ApplyIntentExtractionRequest;
import io.travelos.contracts.trip.v1.GetTripRequest;
import io.travelos.contracts.trip.v1.TransitionTripRequest;
import io.travelos.contracts.trip.v1.TravelCoreServiceGrpc;
import io.travelos.contracts.trip.v1.Trip;
import io.travelos.contracts.trip.v1.UpdateComponentsRequest;
import io.travelos.spring.grpc.GrpcChannels;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/** gRPC adapters. A non-retryable status becomes a non-retryable ApplicationFailure. */
@Component
public class TripActivitiesImpl implements TripActivities {

  private final GrpcChannels channels;
  private final WorkflowProperties properties;

  public TripActivitiesImpl(GrpcChannels channels, WorkflowProperties properties) {
    this.channels = channels;
    this.properties = properties;
  }

  @Override
  public Trip loadTrip(String tenantId, String tripId) {
    return call(
        () ->
            travelCore()
                .getTrip(
                    GetTripRequest.newBuilder()
                        .setCtx(ctx(tenantId, tripId, ""))
                        .setTripId(tripId)
                        .build()));
  }

  @Override
  public Trip transition(TransitionTripRequest request) {
    return call(() -> travelCore().transitionTrip(request));
  }

  @Override
  public ExtractIntentResponse extractIntent(ExtractIntentRequest request) {
    return call(() -> stub(LlmGatewayGrpc::newBlockingStub, "llm-gateway").extractIntent(request));
  }

  @Override
  public Trip applyIntentExtraction(ApplyIntentExtractionRequest request) {
    return call(() -> travelCore().applyIntentExtraction(request));
  }

  @Override
  public ExplainTripResponse explain(ExplainTripRequest request) {
    return call(() -> stub(LlmGatewayGrpc::newBlockingStub, "llm-gateway").explainTrip(request));
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
  public io.travelos.contracts.policy.v1.Governance governance(
      io.travelos.contracts.policy.v1.GetGovernanceRequest request) {
    return call(() -> stub(PolicyServiceGrpc::newBlockingStub, "policy").getGovernance(request));
  }

  @Override
  public io.travelos.contracts.policy.v1.BudgetReservation reserveBudget(
      io.travelos.contracts.policy.v1.ReserveBudgetRequest request) {
    return call(() -> stub(PolicyServiceGrpc::newBlockingStub, "policy").reserveBudget(request));
  }

  @Override
  public OptimizeTripResponse optimize(OptimizeTripRequest request) {
    return call(
        () -> stub(OptimizationServiceGrpc::newBlockingStub, "optimization").optimizeTrip(request));
  }

  @Override
  public Passenger passenger(String tenantId, String tripId, String travelerId) {
    TravelerSnapshotResponse snapshot =
        call(
            () ->
                stub(EnterpriseContextServiceGrpc::newBlockingStub, "enterprise-context")
                    .getTravelerSnapshot(
                        GetTravelerSnapshotRequest.newBuilder()
                            .setCtx(
                                RequestContext.newBuilder()
                                    .setTenantId(tenantId)
                                    .setCorrelationId(tripId)
                                    .setPrincipal(
                                        io.travelos.contracts.common.v1.Principal.newBuilder()
                                            .setKind(
                                                io.travelos.contracts.common.v1.Principal.Kind
                                                    .AGENT)
                                            .setId(TripWorkflowImpl.PRINCIPAL)))
                            .setTravelerId(travelerId)
                            .setIncludeDocuments(true)
                            .setPurpose("BOOKING")
                            .build()));
    PassengerSnapshot p = snapshot.getPassenger();
    Passenger.Builder b =
        Passenger.newBuilder()
            .setGivenName(p.getGivenName())
            .setFamilyName(p.getFamilyName())
            .setEmail(p.getEmail())
            .setPhone(p.getPhone())
            .setDateOfBirth(p.getDateOfBirth())
            .setGender(p.getGender());
    if (p.getLoyaltyCount() > 0) {
      b.setLoyaltyProgram(p.getLoyalty(0).getProgram())
          .setLoyaltyNumber(p.getLoyalty(0).getMemberNumber());
    }
    for (io.travelos.contracts.context.v1.TravelDocument d : p.getDocumentsList()) {
      if (!d.getNumber().isBlank()) {
        b.addDocuments(
            PassengerDocument.newBuilder()
                .setType(d.getType())
                .setNumber(d.getNumber())
                .setIssuingCountry(d.getIssuingCountry())
                .setNationality(d.getNationality())
                .setExpiresOn(d.getExpiresOn()));
      }
    }
    return b.build();
  }

  @Override
  public Order createOrder(CreateOrderCommand command) {
    return call(() -> stub(OrderServiceGrpc::newBlockingStub, "order").createOrder(command));
  }

  @Override
  public SearchHotelsResponse searchHotels(SearchHotelsRequest request) {
    return call(
        () -> stub(SupplierGatewayGrpc::newBlockingStub, "supplier-gateway").searchHotels(request));
  }

  @Override
  public SearchGroundResponse searchGround(SearchGroundRequest request) {
    return call(
        () -> stub(SupplierGatewayGrpc::newBlockingStub, "supplier-gateway").searchGround(request));
  }

  @Override
  public OptimizeItineraryResponse optimizeItinerary(OptimizeItineraryRequest request) {
    return call(
        () ->
            stub(OptimizationServiceGrpc::newBlockingStub, "optimization")
                .optimizeItinerary(request));
  }

  @Override
  public QuoteOfferResponse quote(QuoteOfferRequest request) {
    return call(
        () -> stub(SupplierGatewayGrpc::newBlockingStub, "supplier-gateway").quoteOffer(request));
  }

  @Override
  public Trip updateComponents(UpdateComponentsRequest request) {
    return call(() -> travelCore().updateComponents(request));
  }

  @Override
  public Order cancelOrder(CancelOrderCommand command) {
    return call(() -> stub(OrderServiceGrpc::newBlockingStub, "order").cancelOrder(command));
  }

  @Override
  public Order getOrder(GetOrderRequest request) {
    return call(() -> stub(OrderServiceGrpc::newBlockingStub, "order").getOrder(request));
  }

  /** The workflow's identity on every call. */
  RequestContext ctx(String tenantId, String tripId, String idempotencyKey) {
    return RequestContext.newBuilder()
        .setTenantId(tenantId)
        .setCorrelationId(tripId)
        .setIdempotencyKey(idempotencyKey)
        .setPrincipal(
            Principal.newBuilder().setKind(Principal.Kind.AGENT).setId(properties.principal()))
        .build();
  }

  private TravelCoreServiceGrpc.TravelCoreServiceBlockingStub travelCore() {
    return stub(TravelCoreServiceGrpc::newBlockingStub, "travel-core");
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
      if (isFinal(e.getStatus())) {
        throw ApplicationFailure.newNonRetryableFailure(
            e.getStatus().getCode() + ": " + e.getStatus().getDescription(),
            e.getStatus().getCode().name());
      }
      throw e;
    }
  }

  /** Statuses that will not improve by trying again. */
  public static boolean isFinal(Status status) {
    return switch (status.getCode()) {
      case INVALID_ARGUMENT,
          FAILED_PRECONDITION,
          NOT_FOUND,
          UNIMPLEMENTED,
          PERMISSION_DENIED,
          UNAUTHENTICATED,
          ALREADY_EXISTS,
          OUT_OF_RANGE ->
          true;
      default -> false;
    };
  }
}
