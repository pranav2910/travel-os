package io.travelos.travelcore.context;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.travelos.common.identity.Principal;
import io.travelos.contracts.common.v1.RequestContext;
import io.travelos.contracts.context.v1.ArrangerAuthorization;
import io.travelos.contracts.context.v1.AuthorizeArrangerRequest;
import io.travelos.contracts.context.v1.EnterpriseContextServiceGrpc;
import io.travelos.contracts.context.v1.GetTravelerSnapshotRequest;
import io.travelos.contracts.context.v1.OrgAllocation;
import io.travelos.contracts.context.v1.PassengerSnapshot;
import io.travelos.contracts.context.v1.TravelerSnapshotResponse;
import io.travelos.spring.grpc.GrpcChannels;
import io.travelos.spring.grpc.GrpcClientProperties;
import io.travelos.spring.web.auth.RequestPrincipal;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Travel Core's questions to Enterprise Context: may this person arrange travel for that traveler,
 * and who is that traveler for the suppliers. Optional: without {@code
 * travelos.grpc.clients.enterprise-context.address} the Slice 1 rules apply (self, or MANAGER /
 * TRAVEL_ADMIN for anyone in the tenant) and trips carry no allocation.
 */
@Component
public class ContextClient {
  static final String CLIENT = "enterprise-context";

  /** Enterprise Context could not be reached or did not answer in time. */
  public static final class ContextUnavailableException extends RuntimeException {
    public ContextUnavailableException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  public record Authorization(
      boolean allowed,
      @Nullable String basis,
      @Nullable String reasonCode,
      boolean mayReadDocuments) {}

  /** Names and email as the profile states them, plus the cost allocation, nothing sensitive. */
  public record Snapshot(
      String travelerId,
      String kind,
      String givenName,
      String familyName,
      String email,
      long profileVersion,
      boolean active,
      Allocation allocation) {}

  public record Allocation(
      @Nullable String departmentId,
      @Nullable String costCenterId,
      @Nullable String legalEntityId,
      @Nullable String officeId,
      @Nullable String projectId,
      boolean projectRestricted,
      @Nullable String managerEmployeeId) {}

  private final GrpcChannels channels;
  private final boolean configured;

  public ContextClient(GrpcChannels channels, GrpcClientProperties properties) {
    this.channels = channels;
    GrpcClientProperties.Client c = properties.clients().get(CLIENT);
    this.configured = c != null && c.address() != null && !c.address().isBlank();
  }

  public boolean enabled() {
    return configured;
  }

  public Authorization authorize(
      RequestPrincipal me, String travelerId, @Nullable String projectId) {
    AuthorizeArrangerRequest.Builder request =
        AuthorizeArrangerRequest.newBuilder()
            .setCtx(ctx(me))
            .setArrangerEmployeeId(me.employeeId() == null ? "" : me.employeeId())
            .addAllArrangerRoles(me.roles())
            .setTravelerId(travelerId)
            .setProjectId(projectId == null ? "" : projectId);
    ArrangerAuthorization a = call(() -> stub().authorizeArranger(request.build()));
    return new Authorization(
        a.getAllowed(),
        a.getBasis().isBlank() ? null : a.getBasis(),
        a.getReasonCode().isBlank() ? null : a.getReasonCode(),
        a.getMayReadDocuments());
  }

  /** Empty when Enterprise Context knows no such traveler. */
  public Optional<Snapshot> snapshot(
      RequestPrincipal me, String travelerId, @Nullable String projectId, String purpose) {
    GetTravelerSnapshotRequest request =
        GetTravelerSnapshotRequest.newBuilder()
            .setCtx(ctx(me))
            .setTravelerId(travelerId)
            .setIncludeDocuments(false)
            .setPurpose(purpose)
            .setProjectId(projectId == null ? "" : projectId)
            .setArrangerEmployeeId(me.employeeId() == null ? "" : me.employeeId())
            .addAllArrangerRoles(me.roles())
            .build();
    TravelerSnapshotResponse r;
    try {
      r = call(() -> stub().getTravelerSnapshot(request));
    } catch (StatusRuntimeException e) {
      if (e.getStatus().getCode() == Status.Code.NOT_FOUND) {
        return Optional.empty();
      }
      throw e;
    }
    PassengerSnapshot p = r.getPassenger();
    OrgAllocation a = r.getAllocation();
    return Optional.of(
        new Snapshot(
            p.getTravelerId(),
            p.getKind().isBlank() ? "EMPLOYEE" : p.getKind(),
            p.getGivenName(),
            p.getFamilyName(),
            p.getEmail(),
            p.getProfileVersion().isBlank() ? 0 : Long.parseLong(p.getProfileVersion()),
            p.getActive(),
            new Allocation(
                nul(a.getDepartmentId()),
                nul(a.getCostCenterId()),
                nul(a.getLegalEntityId()),
                nul(a.getOfficeId()),
                nul(a.getProjectId()),
                a.getProjectRestricted(),
                nul(a.getManagerEmployeeId()))));
  }

  private static RequestContext ctx(RequestPrincipal me) {
    io.travelos.contracts.common.v1.Principal.Kind kind =
        switch (me.principal()) {
          case Principal.Human h -> io.travelos.contracts.common.v1.Principal.Kind.HUMAN;
          case Principal.Service s -> io.travelos.contracts.common.v1.Principal.Kind.SERVICE;
          case Principal.Agent a -> io.travelos.contracts.common.v1.Principal.Kind.AGENT;
        };
    return RequestContext.newBuilder()
        .setTenantId(me.tenant().value())
        .setCorrelationId(UUID.randomUUID().toString())
        .setPrincipal(
            io.travelos.contracts.common.v1.Principal.newBuilder()
                .setKind(kind)
                .setId(me.principal().id()))
        .build();
  }

  private EnterpriseContextServiceGrpc.EnterpriseContextServiceBlockingStub stub() {
    return EnterpriseContextServiceGrpc.newBlockingStub(channels.channel(CLIENT))
        .withDeadlineAfter(channels.deadline(CLIENT).toMillis(), TimeUnit.MILLISECONDS);
  }

  private static <T> T call(java.util.function.Supplier<T> call) {
    try {
      return call.get();
    } catch (StatusRuntimeException e) {
      switch (e.getStatus().getCode()) {
        case UNAVAILABLE, DEADLINE_EXCEEDED, RESOURCE_EXHAUSTED, UNKNOWN, INTERNAL ->
            throw new ContextUnavailableException(
                "enterprise-context "
                    + e.getStatus().getCode()
                    + ": "
                    + e.getStatus().getDescription(),
                e);
        default -> throw e;
      }
    }
  }

  private static @Nullable String nul(String s) {
    return s == null || s.isBlank() ? null : s;
  }
}
