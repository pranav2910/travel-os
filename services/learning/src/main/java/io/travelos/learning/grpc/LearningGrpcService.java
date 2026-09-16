package io.travelos.learning.grpc;

import com.google.protobuf.Timestamp;
import io.grpc.stub.StreamObserver;
import io.travelos.contracts.learning.v1.BeginBuildRequest;
import io.travelos.contracts.learning.v1.FailBuildRequest;
import io.travelos.contracts.learning.v1.LearningServiceGrpc;
import io.travelos.contracts.learning.v1.ProfileStepRequest;
import io.travelos.contracts.learning.v1.ProfileSummary;
import io.travelos.contracts.learning.v1.ResolveProfileRequest;
import io.travelos.contracts.optimization.v1.LearningInputs;
import io.travelos.learning.model.Profile;
import io.travelos.learning.service.ProfileService;
import io.travelos.learning.service.ResolveService;
import io.travelos.spring.grpc.RequestContexts;
import java.time.Instant;
import org.springframework.stereotype.Component;

/** The planner's door (resolve) and the build workflow's door (begin, compute, evaluate, fail). */
@Component
public class LearningGrpcService extends LearningServiceGrpc.LearningServiceImplBase {
  private final ResolveService resolve;
  private final ProfileService profiles;

  public LearningGrpcService(ResolveService resolve, ProfileService profiles) {
    this.resolve = resolve;
    this.profiles = profiles;
  }

  @Override
  public void resolveProfile(
      ResolveProfileRequest request, StreamObserver<LearningInputs> observer) {
    RequestContexts.Validated ctx = RequestContexts.require(request.getCtx());
    observer.onNext(resolve.resolve(ctx.tenant(), request.getTravelerId()));
    observer.onCompleted();
  }

  @Override
  public void beginBuild(BeginBuildRequest request, StreamObserver<ProfileSummary> observer) {
    RequestContexts.Validated ctx = RequestContexts.require(request.getCtx());
    Instant cutoff =
        request.hasCutoff()
            ? Instant.ofEpochSecond(
                request.getCutoff().getSeconds(), request.getCutoff().getNanos())
            : null;
    observer.onNext(
        toProto(
            profiles.begin(ctx.tenant(), request.getProfileId(), cutoff, ctx.principal().id())));
    observer.onCompleted();
  }

  @Override
  public void computeProfile(ProfileStepRequest request, StreamObserver<ProfileSummary> observer) {
    RequestContexts.Validated ctx = RequestContexts.require(request.getCtx());
    observer.onNext(toProto(profiles.compute(ctx.tenant(), request.getProfileId())));
    observer.onCompleted();
  }

  @Override
  public void evaluateProfile(ProfileStepRequest request, StreamObserver<ProfileSummary> observer) {
    RequestContexts.Validated ctx = RequestContexts.require(request.getCtx());
    observer.onNext(toProto(profiles.evaluate(ctx.tenant(), request.getProfileId())));
    observer.onCompleted();
  }

  @Override
  public void failBuild(FailBuildRequest request, StreamObserver<ProfileSummary> observer) {
    RequestContexts.Validated ctx = RequestContexts.require(request.getCtx());
    observer.onNext(
        toProto(
            profiles.fail(
                ctx.tenant(),
                request.getProfileId(),
                request.getCode().isBlank() ? "BUILD_FAILED" : request.getCode(),
                request.getMessage().isBlank() ? null : request.getMessage())));
    observer.onCompleted();
  }

  static ProfileSummary toProto(Profile p) {
    ProfileSummary.Builder b =
        ProfileSummary.newBuilder()
            .setProfileId(p.profileId())
            .setStatus(p.status().name())
            .setAlgorithmVersion(p.algorithmVersion())
            .setEvidenceClass(p.evidenceClass().name())
            .setInputCutoff(ts(p.inputCutoff()))
            .setHardOutcomes(p.hardOutcomes())
            .setFeedbackOutcomes(p.feedbackOutcomes())
            .setSupplierKeys(p.supplierKeys());
    if (p.datasetFingerprint() != null) {
      b.setDatasetFingerprint(p.datasetFingerprint());
    }
    if (p.verdict() != null) {
      b.setVerdict(p.verdict());
    }
    if (p.failureCode() != null) {
      b.setFailureCode(p.failureCode());
    }
    return b.build();
  }

  private static Timestamp ts(Instant i) {
    return Timestamp.newBuilder().setSeconds(i.getEpochSecond()).setNanos(i.getNano()).build();
  }
}
