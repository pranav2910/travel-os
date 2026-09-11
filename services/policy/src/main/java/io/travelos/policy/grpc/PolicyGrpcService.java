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

  public PolicyGrpcService(PolicyEvaluationService evaluation) {
    this.evaluation = evaluation;
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
