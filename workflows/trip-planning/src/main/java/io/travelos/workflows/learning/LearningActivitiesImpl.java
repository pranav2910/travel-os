package io.travelos.workflows.learning;

import io.grpc.StatusRuntimeException;
import io.temporal.failure.ApplicationFailure;
import io.travelos.contracts.common.v1.Principal;
import io.travelos.contracts.common.v1.RequestContext;
import io.travelos.contracts.learning.v1.BeginBuildRequest;
import io.travelos.contracts.learning.v1.FailBuildRequest;
import io.travelos.contracts.learning.v1.LearningServiceGrpc;
import io.travelos.contracts.learning.v1.ProfileStepRequest;
import io.travelos.contracts.learning.v1.ProfileSummary;
import io.travelos.contracts.learning.v1.ResolveProfileRequest;
import io.travelos.contracts.optimization.v1.LearningInputs;
import io.travelos.spring.grpc.GrpcChannels;
import io.travelos.workflows.LearningBuild;
import io.travelos.workflows.trip.TripActivitiesImpl;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/** gRPC adapter to the Learning service. A final status becomes a non-retryable failure. */
@Component
public class LearningActivitiesImpl implements LearningActivities {
  private final GrpcChannels channels;

  public LearningActivitiesImpl(GrpcChannels channels) {
    this.channels = channels;
  }

  @Override
  public LearningInputs resolve(String tenantId, String tripId, String travelerId, String purpose) {
    return call(
        () ->
            stub()
                .resolveProfile(
                    ResolveProfileRequest.newBuilder()
                        .setCtx(ctx(tenantId, tripId, "agent/trip-planner/v1"))
                        .setTravelerId(travelerId == null ? "" : travelerId)
                        .setTripId(tripId)
                        .setPurpose(purpose == null ? "" : purpose)
                        .build()));
  }

  @Override
  public ProfileSummary beginBuild(String tenantId, String profileId) {
    return call(
        () ->
            stub()
                .beginBuild(
                    BeginBuildRequest.newBuilder()
                        .setCtx(ctx(tenantId, profileId, LearningBuild.AGENT))
                        .setProfileId(profileId)
                        .build()));
  }

  @Override
  public ProfileSummary computeProfile(String tenantId, String profileId) {
    return call(() -> stub().computeProfile(step(tenantId, profileId)));
  }

  @Override
  public ProfileSummary evaluateProfile(String tenantId, String profileId) {
    return call(() -> stub().evaluateProfile(step(tenantId, profileId)));
  }

  @Override
  public ProfileSummary failBuild(String tenantId, String profileId, String code, String message) {
    return call(
        () ->
            stub()
                .failBuild(
                    FailBuildRequest.newBuilder()
                        .setCtx(ctx(tenantId, profileId, LearningBuild.AGENT))
                        .setProfileId(profileId)
                        .setCode(code == null ? "" : code)
                        .setMessage(message == null ? "" : message)
                        .build()));
  }

  private static ProfileStepRequest step(String tenantId, String profileId) {
    return ProfileStepRequest.newBuilder()
        .setCtx(ctx(tenantId, profileId, LearningBuild.AGENT))
        .setProfileId(profileId)
        .build();
  }

  private LearningServiceGrpc.LearningServiceBlockingStub stub() {
    return LearningServiceGrpc.newBlockingStub(channels.channel("learning"))
        .withDeadlineAfter(channels.deadline("learning").toMillis(), TimeUnit.MILLISECONDS);
  }

  private static RequestContext ctx(String tenantId, String correlationId, String agent) {
    return RequestContext.newBuilder()
        .setTenantId(tenantId)
        .setCorrelationId(correlationId)
        .setPrincipal(Principal.newBuilder().setKind(Principal.Kind.AGENT).setId(agent))
        .build();
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
