package io.travelos.disruption.grpc;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.travelos.contracts.disruption.v1.Disruption;
import io.travelos.contracts.disruption.v1.DisruptionServiceGrpc;
import io.travelos.contracts.disruption.v1.DisruptionStatus;
import io.travelos.contracts.disruption.v1.GetDisruptionRequest;
import io.travelos.contracts.disruption.v1.RecordRecoveryDecisionRequest;
import io.travelos.contracts.disruption.v1.RecordRecoveryOutcomeRequest;
import io.travelos.contracts.disruption.v1.TransitionDisruptionRequest;
import io.travelos.disruption.service.DisruptionService;
import io.travelos.spring.grpc.RequestContexts;
import org.springframework.stereotype.Component;

/** The recovery workflow's door into the Disruption service. */
@Component
public class DisruptionGrpcService extends DisruptionServiceGrpc.DisruptionServiceImplBase {

  private final DisruptionService disruptions;

  public DisruptionGrpcService(DisruptionService disruptions) {
    this.disruptions = disruptions;
  }

  @Override
  public void getDisruption(GetDisruptionRequest request, StreamObserver<Disruption> observer) {
    RequestContexts.Validated ctx = RequestContexts.require(request.getCtx());
    observer.onNext(full(disruptions.get(ctx.tenant(), request.getDisruptionId())));
    observer.onCompleted();
  }

  @Override
  public void transitionDisruption(
      TransitionDisruptionRequest request, StreamObserver<Disruption> observer) {
    RequestContexts.Validated ctx = RequestContexts.require(request.getCtx());
    if (request.getTo() == DisruptionStatus.DISRUPTION_STATUS_UNSPECIFIED) {
      throw Status.INVALID_ARGUMENT.withDescription("to is required").asRuntimeException();
    }
    observer.onNext(
        full(
            disruptions.transition(
                ctx.tenant(),
                request.getDisruptionId(),
                request.getTo(),
                blankToNull(request.getReason()),
                blankToNull(request.getApproverRole()),
                request.hasRecovery() ? request.getRecovery() : null,
                blankToNull(request.getFailureStage()),
                blankToNull(request.getFailureCode()))));
    observer.onCompleted();
  }

  @Override
  public void recordRecoveryDecision(
      RecordRecoveryDecisionRequest request, StreamObserver<Disruption> observer) {
    RequestContexts.Validated ctx = RequestContexts.require(request.getCtx());
    if (!request.hasDecision() || !request.getDecision().hasSelected()) {
      throw Status.INVALID_ARGUMENT
          .withDescription("decision with a selected bundle is required")
          .asRuntimeException();
    }
    observer.onNext(
        full(
            disruptions.recordDecision(
                ctx.tenant(), request.getDisruptionId(), request.getDecision())));
    observer.onCompleted();
  }

  @Override
  public void recordRecoveryOutcome(
      RecordRecoveryOutcomeRequest request, StreamObserver<Disruption> observer) {
    RequestContexts.Validated ctx = RequestContexts.require(request.getCtx());
    if (!request.hasOutcome()) {
      throw Status.INVALID_ARGUMENT.withDescription("outcome is required").asRuntimeException();
    }
    observer.onNext(
        full(
            disruptions.recordOutcome(
                ctx.tenant(), request.getDisruptionId(), request.getOutcome())));
    observer.onCompleted();
  }

  private Disruption full(io.travelos.disruption.model.Disruption d) {
    return ProtoMapping.toProto(
        d,
        disruptions.decisionJson(d.tenant(), d.disruptionId()),
        disruptions.outcomeJson(d.tenant(), d.disruptionId()));
  }

  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s;
  }
}
