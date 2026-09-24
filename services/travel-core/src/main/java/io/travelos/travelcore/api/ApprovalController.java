package io.travelos.travelcore.api;

import io.travelos.spring.web.auth.RequestPrincipal;
import io.travelos.spring.web.idempotency.IdempotencyKeyHeader;
import io.travelos.travelcore.approval.Approval;
import io.travelos.travelcore.trip.TripService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** A manager decides. The workflow is signalled; the trip moves when the workflow says so. */
@RestController
@RequestMapping(path = "/api/v1/trips/{tripId}/approval", produces = "application/json")
public class ApprovalController {

  private final TripService trips;

  public ApprovalController(TripService trips) {
    this.trips = trips;
  }

  public enum Decision {
    APPROVE,
    REJECT
  }

  public record DecisionRequest(
      @NotNull Decision decision, @Nullable @Size(max = 2000) String comment) {}

  public record ApprovalResponse(
      String approvalId,
      String tripId,
      String requiredRole,
      String status,
      @Nullable String policyDecisionId,
      Instant requestedAt,
      @Nullable String decidedBy,
      @Nullable Instant decidedAt,
      @Nullable String comment,
      int step,
      int chainLength,
      java.util.List<String> chainRoles,
      @Nullable Instant expiresAt,
      @Nullable Instant escalatedAt,
      @Nullable String escalatedToRole,
      @Nullable String onBehalfOf) {

    public static ApprovalResponse from(Approval a) {
      return new ApprovalResponse(
          a.approvalId(),
          a.tripId(),
          a.requiredRole(),
          a.status().name(),
          a.policyDecisionId(),
          a.requestedAt(),
          a.decidedBy(),
          a.decidedAt(),
          a.comment(),
          a.step(),
          a.chainLength(),
          a.chainRoles(),
          a.expiresAt(),
          a.escalatedAt(),
          a.escalatedToRole(),
          a.onBehalfOf());
    }
  }

  @PostMapping(consumes = "application/json")
  public ApprovalResponse decide(
      @AuthenticationPrincipal RequestPrincipal me,
      @PathVariable String tripId,
      @IdempotencyKeyHeader String idempotencyKey,
      @Valid @RequestBody DecisionRequest request) {
    Approval.Status decision =
        request.decision() == Decision.APPROVE
            ? Approval.Status.APPROVED
            : Approval.Status.REJECTED;
    return ApprovalResponse.from(
        trips.decideApproval(me, tripId, decision, request.comment(), idempotencyKey));
  }
}
