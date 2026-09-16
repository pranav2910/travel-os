package io.travelos.workflows.demand;

import io.grpc.StatusRuntimeException;
import io.temporal.failure.ApplicationFailure;
import io.travelos.contracts.common.v1.Principal;
import io.travelos.contracts.common.v1.RequestContext;
import io.travelos.contracts.context.v1.CompleteSyncRequest;
import io.travelos.contracts.context.v1.EnterpriseContextServiceGrpc;
import io.travelos.contracts.context.v1.FailSyncRequest;
import io.travelos.contracts.context.v1.SyncPageRequest;
import io.travelos.contracts.context.v1.SyncPageResponse;
import io.travelos.contracts.context.v1.SyncRun;
import io.travelos.spring.grpc.GrpcChannels;
import io.travelos.workflows.DemandSync;
import io.travelos.workflows.trip.TripActivitiesImpl;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/** gRPC adapter to Enterprise Context. A final status becomes a non-retryable failure. */
@Component
public class DemandActivitiesImpl implements DemandActivities {
  private final GrpcChannels channels;

  public DemandActivitiesImpl(GrpcChannels channels) {
    this.channels = channels;
  }

  @Override
  public SyncPageResponse syncPage(
      String tenantId, String connectorId, String runId, String cursor) {
    return call(
        () ->
            stub()
                .syncPage(
                    SyncPageRequest.newBuilder()
                        .setCtx(ctx(tenantId, runId))
                        .setConnectorId(connectorId)
                        .setRunId(runId)
                        .setCursor(cursor == null ? "" : cursor)
                        .build()));
  }

  @Override
  public SyncRun completeSync(String tenantId, String connectorId, String runId) {
    return call(
        () ->
            stub()
                .completeSync(
                    CompleteSyncRequest.newBuilder()
                        .setCtx(ctx(tenantId, runId))
                        .setConnectorId(connectorId)
                        .setRunId(runId)
                        .build()));
  }

  @Override
  public SyncRun failSync(
      String tenantId, String connectorId, String runId, String code, String message) {
    return call(
        () ->
            stub()
                .failSync(
                    FailSyncRequest.newBuilder()
                        .setCtx(ctx(tenantId, runId))
                        .setConnectorId(connectorId)
                        .setRunId(runId)
                        .setCode(code == null ? "" : code)
                        .setMessage(message == null ? "" : message)
                        .build()));
  }

  private EnterpriseContextServiceGrpc.EnterpriseContextServiceBlockingStub stub() {
    return EnterpriseContextServiceGrpc.newBlockingStub(channels.channel("enterprise-context"))
        .withDeadlineAfter(
            channels.deadline("enterprise-context").toMillis(), TimeUnit.MILLISECONDS);
  }

  private static RequestContext ctx(String tenantId, String runId) {
    return RequestContext.newBuilder()
        .setTenantId(tenantId)
        .setCorrelationId(runId)
        .setPrincipal(Principal.newBuilder().setKind(Principal.Kind.AGENT).setId(DemandSync.AGENT))
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
