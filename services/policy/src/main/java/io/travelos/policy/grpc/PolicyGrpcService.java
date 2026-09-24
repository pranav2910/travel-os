package io.travelos.policy.grpc;

import io.grpc.stub.StreamObserver;
import io.travelos.contracts.policy.v1.EvaluateActionRequest;
import io.travelos.contracts.policy.v1.EvaluateTripRequest;
import io.travelos.contracts.policy.v1.EvaluateTripResponse;
import io.travelos.contracts.policy.v1.PolicyDecision;
import io.travelos.contracts.policy.v1.PolicyServiceGrpc;
import io.travelos.policy.evaluation.PolicyEvaluationService;
import org.springframework.stereotype.Component;

/** Thin gRPC edge. Exceptions are mapped to statuses by the server interceptor. */
@Component
public class PolicyGrpcService extends PolicyServiceGrpc.PolicyServiceImplBase {

  private final PolicyEvaluationService evaluation;
  private final io.travelos.policy.governance.GovernanceService governance;
  private final java.time.Clock clock;

  public PolicyGrpcService(
      PolicyEvaluationService evaluation,
      io.travelos.policy.governance.GovernanceService governance,
      java.time.Clock clock) {
    this.evaluation = evaluation;
    this.governance = governance;
    this.clock = clock;
  }

  @Override
  public void getGovernance(
      io.travelos.contracts.policy.v1.GetGovernanceRequest request,
      StreamObserver<io.travelos.contracts.policy.v1.Governance> responseObserver) {
    io.travelos.spring.grpc.RequestContexts.Validated ctx =
        io.travelos.spring.grpc.RequestContexts.require(request.getCtx());
    io.travelos.policy.governance.GovernanceService.Resolved r =
        governance.resolve(
            ctx.tenant(),
            PolicyEvaluationService.scope(
                request.getTravelerId(), request.hasScope() ? request.getScope() : null),
            clock.instant());
    io.travelos.contracts.policy.v1.Governance.Builder b =
        io.travelos.contracts.policy.v1.Governance.newBuilder()
            .setPolicyScopeKind(r.policyScopeKind());
    r.policy().ifPresent(p -> b.setPolicyId(p.policyId()).setPolicyVersion(p.version()));
    for (io.travelos.policy.governance.GovernanceRecords.Agreement a : r.agreements()) {
      io.travelos.contracts.policy.v1.SupplierAgreement.Builder sa =
          io.travelos.contracts.policy.v1.SupplierAgreement.newBuilder()
              .setAgreementId(a.agreementId())
              .setProvider(a.provider())
              .setKind(a.kind())
              .setCarrier(a.carrier() == null ? "" : a.carrier())
              .setRateCode(a.rateCode() == null ? "" : a.rateCode())
              .setPreferred(a.preferred())
              .setNegotiated(a.negotiated());
      if (a.validUntil() != null) {
        sa.setValidUntil(
            com.google.protobuf.Timestamp.newBuilder().setSeconds(a.validUntil().getEpochSecond()));
      }
      b.addAgreements(sa);
    }
    responseObserver.onNext(b.build());
    responseObserver.onCompleted();
  }

  @Override
  public void reserveBudget(
      io.travelos.contracts.policy.v1.ReserveBudgetRequest request,
      StreamObserver<io.travelos.contracts.policy.v1.BudgetReservation> responseObserver) {
    io.travelos.spring.grpc.RequestContexts.Validated ctx =
        io.travelos.spring.grpc.RequestContexts.require(request.getCtx());
    if (request.getTripId().isBlank() || !request.hasAmount()) {
      throw io.grpc.Status.INVALID_ARGUMENT
          .withDescription("trip_id and amount are required")
          .asRuntimeException();
    }
    io.travelos.policy.governance.GovernanceService.ReserveResult r =
        governance.reserve(
            ctx.tenant(),
            request.getTripId(),
            request.getTravelerId().isBlank() ? null : request.getTravelerId(),
            PolicyEvaluationService.scope(
                request.getTravelerId(), request.hasScope() ? request.getScope() : null),
            io.travelos.common.money.Money.of(
                request.getAmount().getCurrency(), request.getAmount().getAmountMinor()));
    responseObserver.onNext(reservation(r));
    responseObserver.onCompleted();
  }

  @Override
  public void settleBudget(
      io.travelos.contracts.policy.v1.SettleBudgetRequest request,
      StreamObserver<io.travelos.contracts.policy.v1.BudgetReservation> responseObserver) {
    io.travelos.spring.grpc.RequestContexts.Validated ctx =
        io.travelos.spring.grpc.RequestContexts.require(request.getCtx());
    boolean commit = "COMMIT".equalsIgnoreCase(request.getOutcome());
    if (!commit && !"RELEASE".equalsIgnoreCase(request.getOutcome())) {
      throw io.grpc.Status.INVALID_ARGUMENT
          .withDescription("outcome must be COMMIT or RELEASE")
          .asRuntimeException();
    }
    io.travelos.policy.governance.GovernanceService.ReserveResult r =
        governance.settle(
            ctx.tenant(),
            request.getTripId(),
            commit,
            request.hasAmount()
                ? io.travelos.common.money.Money.of(
                    request.getAmount().getCurrency(), request.getAmount().getAmountMinor())
                : null);
    responseObserver.onNext(reservation(r));
    responseObserver.onCompleted();
  }

  private static io.travelos.contracts.policy.v1.BudgetReservation reservation(
      io.travelos.policy.governance.GovernanceService.ReserveResult r) {
    io.travelos.contracts.policy.v1.BudgetReservation.Builder b =
        io.travelos.contracts.policy.v1.BudgetReservation.newBuilder()
            .setStatus(io.travelos.contracts.policy.v1.BudgetStatus.valueOf(r.status().name()))
            .setHard(r.hard())
            .setMessage(r.message());
    if (r.budgetId() != null) {
      b.setBudgetId(r.budgetId());
    }
    if (r.reservationId() != null) {
      b.setReservationId(r.reservationId());
    }
    if (r.remaining() != null) {
      b.setRemaining(
          io.travelos.contracts.common.v1.Money.newBuilder()
              .setCurrency(r.remaining().currency())
              .setAmountMinor(r.remaining().amountMinor()));
    }
    return b.build();
  }

  @Override
  public void evaluateTrip(
      EvaluateTripRequest request, StreamObserver<EvaluateTripResponse> responseObserver) {
    responseObserver.onNext(evaluation.evaluateTrip(request));
    responseObserver.onCompleted();
  }

  @Override
  public void evaluateAction(
      EvaluateActionRequest request, StreamObserver<PolicyDecision> responseObserver) {
    responseObserver.onNext(evaluation.evaluateAction(request));
    responseObserver.onCompleted();
  }
}
