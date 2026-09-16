package io.travelos.context.grpc;

import com.google.protobuf.Timestamp;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.travelos.context.model.Employee;
import io.travelos.context.service.SyncService;
import io.travelos.context.store.EmployeeRepository;
import io.travelos.contracts.context.v1.CompleteSyncRequest;
import io.travelos.contracts.context.v1.EnterpriseContextServiceGrpc;
import io.travelos.contracts.context.v1.FailSyncRequest;
import io.travelos.contracts.context.v1.GetEmployeeRequest;
import io.travelos.contracts.context.v1.SyncPageRequest;
import io.travelos.contracts.context.v1.SyncPageResponse;
import io.travelos.contracts.context.v1.SyncRun;
import io.travelos.spring.grpc.RequestContexts;
import java.time.Instant;
import org.springframework.stereotype.Component;

/** The sync workflow's door into Enterprise Context, and the directory for other services. */
@Component
public class ContextGrpcService
    extends EnterpriseContextServiceGrpc.EnterpriseContextServiceImplBase {
  private final SyncService sync;
  private final EmployeeRepository employees;

  public ContextGrpcService(SyncService sync, EmployeeRepository employees) {
    this.sync = sync;
    this.employees = employees;
  }

  @Override
  public void syncPage(SyncPageRequest request, StreamObserver<SyncPageResponse> observer) {
    RequestContexts.Validated ctx = RequestContexts.require(request.getCtx());
    SyncService.PageResult r =
        sync.syncPage(
            ctx.tenant(), request.getConnectorId(), request.getRunId(), request.getCursor());
    observer.onNext(
        SyncPageResponse.newBuilder()
            .setNextCursor(r.nextCursor())
            .setDone(r.done())
            .setItemsSeen(r.itemsSeen())
            .setItemsChanged(r.itemsChanged())
            .setCandidatesTouched(r.candidatesTouched())
            .build());
    observer.onCompleted();
  }

  @Override
  public void completeSync(CompleteSyncRequest request, StreamObserver<SyncRun> observer) {
    RequestContexts.Validated ctx = RequestContexts.require(request.getCtx());
    observer.onNext(
        toProto(sync.completeSync(ctx.tenant(), request.getConnectorId(), request.getRunId())));
    observer.onCompleted();
  }

  @Override
  public void failSync(FailSyncRequest request, StreamObserver<SyncRun> observer) {
    RequestContexts.Validated ctx = RequestContexts.require(request.getCtx());
    observer.onNext(
        toProto(
            sync.failSync(
                ctx.tenant(),
                request.getConnectorId(),
                request.getRunId(),
                request.getCode().isBlank() ? "SYNC_FAILED" : request.getCode(),
                request.getMessage().isBlank() ? null : request.getMessage())));
    observer.onCompleted();
  }

  @Override
  public void getEmployee(
      GetEmployeeRequest request,
      StreamObserver<io.travelos.contracts.context.v1.Employee> observer) {
    RequestContexts.Validated ctx = RequestContexts.require(request.getCtx());
    Employee e =
        employees
            .find(ctx.tenant(), request.getEmployeeId())
            .orElseThrow(
                () ->
                    Status.NOT_FOUND
                        .withDescription("employee " + request.getEmployeeId())
                        .asRuntimeException());
    observer.onNext(
        io.travelos.contracts.context.v1.Employee.newBuilder()
            .setEmployeeId(e.employeeId())
            .setDisplayName(e.displayName())
            .setEmail(e.email())
            .setWorkLocation(e.workLocation())
            .setTimeZone(e.timeZone().getId())
            .setManagerEmployeeId(e.managerEmployeeId() == null ? "" : e.managerEmployeeId())
            .setActive(e.active())
            .build());
    observer.onCompleted();
  }

  static SyncRun toProto(io.travelos.context.model.SyncRun r) {
    SyncRun.Builder b =
        SyncRun.newBuilder()
            .setRunId(r.runId())
            .setConnectorId(r.connectorId())
            .setStatus(r.status().name())
            .setStartedAt(ts(r.startedAt()))
            .setPages(r.pages())
            .setItemsSeen(r.itemsSeen())
            .setItemsChanged(r.itemsChanged())
            .setCandidatesTouched(r.candidatesTouched());
    if (r.finishedAt() != null) {
      b.setFinishedAt(ts(r.finishedAt()));
    }
    if (r.failureCode() != null) {
      b.setFailureCode(r.failureCode());
    }
    return b.build();
  }

  private static Timestamp ts(Instant i) {
    return Timestamp.newBuilder().setSeconds(i.getEpochSecond()).setNanos(i.getNano()).build();
  }
}
