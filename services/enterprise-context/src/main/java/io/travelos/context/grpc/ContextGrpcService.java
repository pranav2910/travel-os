package io.travelos.context.grpc;

import com.google.protobuf.Timestamp;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.travelos.context.model.Employee;
import io.travelos.context.model.TravelDocument;
import io.travelos.context.model.TravelerProfile;
import io.travelos.context.service.ArrangerService;
import io.travelos.context.service.OrgService;
import io.travelos.context.service.ProfileAccess;
import io.travelos.context.service.ProfileService;
import io.travelos.context.service.SyncService;
import io.travelos.context.store.EmployeeRepository;
import io.travelos.context.store.ProfileRepository;
import io.travelos.contracts.context.v1.ArrangerAuthorization;
import io.travelos.contracts.context.v1.AuthorizeArrangerRequest;
import io.travelos.contracts.context.v1.CompleteSyncRequest;
import io.travelos.contracts.context.v1.EnterpriseContextServiceGrpc;
import io.travelos.contracts.context.v1.FailSyncRequest;
import io.travelos.contracts.context.v1.GetEmployeeRequest;
import io.travelos.contracts.context.v1.GetTravelerSnapshotRequest;
import io.travelos.contracts.context.v1.LoyaltyMembership;
import io.travelos.contracts.context.v1.OrgAllocation;
import io.travelos.contracts.context.v1.PassengerSnapshot;
import io.travelos.contracts.context.v1.SyncPageRequest;
import io.travelos.contracts.context.v1.SyncPageResponse;
import io.travelos.contracts.context.v1.SyncRun;
import io.travelos.contracts.context.v1.TravelerSnapshotResponse;
import io.travelos.spring.grpc.RequestContexts;
import io.travelos.spring.web.error.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** The sync workflow's door into Enterprise Context, and the directory for other services. */
@Component
public class ContextGrpcService
    extends EnterpriseContextServiceGrpc.EnterpriseContextServiceImplBase {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private final SyncService sync;
  private final EmployeeRepository employees;
  private final ProfileService profiles;
  private final ProfileRepository profileStore;
  private final ArrangerService arrangers;
  private final OrgService org;
  private final Clock clock;

  public ContextGrpcService(
      SyncService sync,
      EmployeeRepository employees,
      ProfileService profiles,
      ProfileRepository profileStore,
      ArrangerService arrangers,
      OrgService org,
      Clock clock) {
    this.sync = sync;
    this.employees = employees;
    this.profiles = profiles;
    this.profileStore = profileStore;
    this.arrangers = arrangers;
    this.org = org;
    this.clock = clock;
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

  /**
   * The passenger as the suppliers need them, shaped by the arranger's relationship to the
   * traveler. Sensitive values are disclosed only to those allowed to see them, and every
   * disclosure is logged with the stated purpose. Unknown travelers are NOT_FOUND; an arranger with
   * no relationship at all gets NOT_FOUND too (existence is not disclosed).
   */
  @Override
  public void getTravelerSnapshot(
      GetTravelerSnapshotRequest request, StreamObserver<TravelerSnapshotResponse> observer) {
    RequestContexts.Validated ctx = RequestContexts.require(request.getCtx());
    ProfileAccess.Caller caller =
        new ProfileAccess.Caller(
            request.getArrangerEmployeeId().isBlank() ? null : request.getArrangerEmployeeId(),
            new HashSet<>(request.getArrangerRolesList()));
    String purpose = request.getPurpose().isBlank() ? "SNAPSHOT" : request.getPurpose();
    ProfileService.View view;
    try {
      view =
          profiles.get(
              ctx.tenant(), caller, ctx.principal().id(), request.getTravelerId(), true, purpose);
    } catch (ApiException.NotFound e) {
      observer.onError(Status.NOT_FOUND.withDescription(e.getMessage()).asRuntimeException());
      return;
    }
    TravelerProfile p = view.profile();
    boolean documents =
        request.getIncludeDocuments() && ProfileAccess.mayRevealDocuments(view.relation());
    PassengerSnapshot.Builder passenger =
        PassengerSnapshot.newBuilder()
            .setTravelerId(p.travelerId())
            .setKind(p.kind().name())
            .setGivenName(p.givenName())
            .setFamilyName(p.familyName())
            .setMiddleName(nz(p.middleName()))
            .setEmail(p.email())
            .setPhone(nz(p.phone()))
            .setDateOfBirth(p.dateOfBirth() == null ? "" : p.dateOfBirth().toString())
            .setGender(nz(p.gender()))
            .setNationality(nz(p.nationality()))
            .setHomeAirport(nz(p.homeAirport()))
            .setActive(p.active())
            .setProfileVersion(String.valueOf(p.version()))
            .setPreferencesJson(JSON.writeValueAsString(p.preferences()))
            .setSponsorEmployeeId(nz(p.sponsorEmployeeId()));
    for (TravelerProfile.Loyalty l : p.loyalty()) {
      passenger.addLoyalty(
          LoyaltyMembership.newBuilder()
              .setProgram(l.program())
              .setMemberNumber(nz(l.memberNumber()))
              .setMemberNumberLast4(nz(l.last4()))
              .build());
    }
    if (ProfileAccess.mayReadProfile(view.relation())) {
      for (TravelDocument d : profileStore.documents(ctx.tenant(), p.travelerId(), documents)) {
        passenger.addDocuments(
            io.travelos.contracts.context.v1.TravelDocument.newBuilder()
                .setDocumentId(d.documentId())
                .setType(d.type().name())
                .setNumber(nz(d.number()))
                .setNumberLast4(d.numberLast4())
                .setIssuingCountry(d.issuingCountry())
                .setNationality(nz(d.nationality()))
                .setExpiresOn(d.expiresOn().toString())
                .setHolderGivenName(d.holderGivenName())
                .setHolderFamilyName(d.holderFamilyName())
                .build());
      }
      if (documents) {
        profileStore.logSensitiveAccess(
            ctx.tenant(),
            ctx.principal().id(),
            p.travelerId(),
            "DOCUMENTS",
            purpose,
            clock.instant());
      }
    }
    OrgService.Allocation a =
        org.allocation(
            ctx.tenant(),
            p.travelerId(),
            request.getProjectId().isBlank() ? null : request.getProjectId());
    OrgAllocation allocation =
        OrgAllocation.newBuilder()
            .setDepartmentId(nz(a.departmentId()))
            .setDepartmentCode(nz(a.departmentCode()))
            .setCostCenterId(nz(a.costCenterId()))
            .setCostCenterCode(nz(a.costCenterCode()))
            .setLegalEntityId(nz(a.legalEntityId()))
            .setLegalEntityCode(nz(a.legalEntityCode()))
            .setOfficeId(nz(a.officeId()))
            .setProjectId(nz(a.projectId()))
            .setProjectCode(nz(a.projectCode()))
            .setProjectRestricted(a.projectRestricted())
            .setManagerEmployeeId(nz(a.managerEmployeeId()))
            .build();
    observer.onNext(
        TravelerSnapshotResponse.newBuilder()
            .setPassenger(passenger)
            .setAllocation(allocation)
            .build());
    observer.onCompleted();
  }

  @Override
  public void authorizeArranger(
      AuthorizeArrangerRequest request, StreamObserver<ArrangerAuthorization> observer) {
    RequestContexts.Validated ctx = RequestContexts.require(request.getCtx());
    Set<String> roles = new HashSet<>(request.getArrangerRolesList());
    ArrangerService.Authorization a =
        arrangers.authorize(
            ctx.tenant(),
            new ProfileAccess.Caller(
                request.getArrangerEmployeeId().isBlank() ? null : request.getArrangerEmployeeId(),
                roles),
            request.getTravelerId(),
            request.getProjectId().isBlank() ? null : request.getProjectId());
    observer.onNext(
        ArrangerAuthorization.newBuilder()
            .setAllowed(a.allowed())
            .setBasis(nz(a.basis()))
            .setReasonCode(nz(a.reasonCode()))
            .setMayReadDocuments(a.mayReadDocuments())
            .build());
    observer.onCompleted();
  }

  private static String nz(String s) {
    return s == null ? "" : s;
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
