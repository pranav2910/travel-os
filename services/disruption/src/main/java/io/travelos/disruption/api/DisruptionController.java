package io.travelos.disruption.api;

import io.travelos.disruption.model.Disruption;
import io.travelos.disruption.model.RecoveryApproval;
import io.travelos.disruption.service.ChangeRequestService;
import io.travelos.disruption.service.DisruptionService;
import io.travelos.disruption.service.DisruptionService.DisruptionAccess;
import io.travelos.spring.web.auth.RequestPrincipal;
import io.travelos.spring.web.error.ApiException;
import io.travelos.spring.web.idempotency.IdempotencyKeyHeader;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * What happened to my trip, and why THIS replacement: the disruption, the recovery state, the
 * immutable decision record (candidates, rejections and their policy reasons, the selected
 * itinerary with its score, the incremental cost, the policy verdict), the outcome, and the
 * history.
 */
@RestController
@RequestMapping(path = "/api/v1", produces = "application/json")
public class DisruptionController {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final DisruptionService disruptions;
  private final ChangeRequestService requests;

  public DisruptionController(DisruptionService disruptions, ChangeRequestService requests) {
    this.disruptions = disruptions;
    this.requests = requests;
  }

  /** Phase 6: a traveler asks to move a booked flight into a window; see ChangeRequestService. */
  public record ChangeRequestBody(
      @NotBlank @Size(max = 64) String tripId,
      @NotBlank @Size(max = 64) String orderId,
      @NotBlank @Size(max = 64) String componentId,
      @NotNull Instant notBefore,
      @Nullable Instant notAfter,
      @Nullable @Size(max = 1000) String reason) {}

  @PostMapping(path = "/disruptions/requests", consumes = "application/json")
  public org.springframework.http.ResponseEntity<DisruptionView> request(
      @AuthenticationPrincipal RequestPrincipal me,
      @IdempotencyKeyHeader String idempotencyKey,
      @Valid @RequestBody ChangeRequestBody body) {
    Disruption d =
        requests.request(
            me,
            new ChangeRequestService.ChangeRequest(
                body.tripId(),
                body.orderId(),
                body.componentId(),
                body.notBefore(),
                body.notAfter(),
                body.reason()),
            idempotencyKey);
    return org.springframework.http.ResponseEntity.status(
            org.springframework.http.HttpStatus.CREATED)
        .body(view(d));
  }

  public record StatusChange(
      @Nullable String from, String to, @Nullable String reason, String occurredAt) {}

  public record ApprovalView(
      String approvalId,
      String requiredRole,
      String status,
      @Nullable String decidedBy,
      @Nullable Instant decidedAt,
      @Nullable String comment) {
    static ApprovalView from(RecoveryApproval a) {
      return new ApprovalView(
          a.approvalId(),
          a.requiredRole(),
          a.status().name(),
          a.decidedBy(),
          a.decidedAt(),
          a.comment());
    }
  }

  public record DisruptionView(
      String disruptionId,
      String tenantId,
      @Nullable String tripId,
      @Nullable String orderId,
      @Nullable String travelerId,
      @Nullable String segmentId,
      String type,
      String supplier,
      String supplierEventId,
      String externalOrderId,
      @Nullable String recordLocator,
      Instant detectedAt,
      String status,
      String severity,
      @Nullable String rawReference,
      @Nullable String reason,
      JsonNode affected,
      JsonNode recovery,
      @Nullable String failureStage,
      @Nullable String failureCode,
      @Nullable JsonNode decision,
      @Nullable JsonNode outcome,
      @Nullable ApprovalView approval,
      List<StatusChange> history,
      long version,
      Instant createdAt,
      Instant updatedAt) {}

  @GetMapping("/trips/{tripId}/disruptions")
  public List<DisruptionView> byTrip(
      @AuthenticationPrincipal RequestPrincipal me, @PathVariable String tripId) {
    return disruptions.byTrip(me.tenant(), tripId).stream()
        .filter(d -> DisruptionAccess.canRead(me, d))
        .map(this::view)
        .toList();
  }

  /**
   * The tenant's disruptions the caller may read, optionally at one status: a manager's inbox is
   * {@code status=HUMAN_REQUIRED}. A traveler sees only their own.
   */
  @GetMapping("/disruptions")
  public List<DisruptionView> list(
      @AuthenticationPrincipal RequestPrincipal me,
      @RequestParam(required = false) @Nullable String status,
      @RequestParam(defaultValue = "50") int limit) {
    io.travelos.disruption.model.DisruptionStatus wanted = null;
    if (status != null && !status.isBlank()) {
      try {
        wanted =
            io.travelos.disruption.model.DisruptionStatus.valueOf(
                status.trim().toUpperCase(java.util.Locale.ROOT));
      } catch (IllegalArgumentException e) {
        throw new ApiException.Unprocessable("STATUS_UNKNOWN", "unknown status " + status);
      }
    }
    return disruptions.list(me.tenant(), wanted, Math.clamp(limit, 1, 200)).stream()
        .filter(d -> DisruptionAccess.canRead(me, d))
        .map(this::view)
        .toList();
  }

  @GetMapping("/disruptions/{disruptionId}")
  public DisruptionView get(
      @AuthenticationPrincipal RequestPrincipal me, @PathVariable String disruptionId) {
    Disruption d;
    try {
      d = disruptions.get(me.tenant(), disruptionId);
    } catch (io.grpc.StatusRuntimeException e) {
      throw new ApiException.NotFound("disruption", disruptionId);
    }
    if (!DisruptionAccess.canRead(me, d)) {
      throw new ApiException.NotFound("disruption", disruptionId);
    }
    return view(d);
  }

  public enum Decision {
    APPROVE,
    REJECT
  }

  public record DecisionRequest(
      @NotNull Decision decision, @Nullable @Size(max = 2000) String comment) {}

  @PostMapping(path = "/disruptions/{disruptionId}/approval", consumes = "application/json")
  public ApprovalView decide(
      @AuthenticationPrincipal RequestPrincipal me,
      @PathVariable String disruptionId,
      @IdempotencyKeyHeader String idempotencyKey,
      @Valid @RequestBody DecisionRequest request) {
    RecoveryApproval.Status decision =
        request.decision() == Decision.APPROVE
            ? RecoveryApproval.Status.APPROVED
            : RecoveryApproval.Status.REJECTED;
    return ApprovalView.from(
        disruptions.decideApproval(me, disruptionId, decision, request.comment(), idempotencyKey));
  }

  private DisruptionView view(Disruption d) {
    Optional<String> decision = disruptions.decisionJson(d.tenant(), d.disruptionId());
    Optional<String> outcome = disruptions.outcomeJson(d.tenant(), d.disruptionId());
    Optional<RecoveryApproval> approval = disruptions.latestApproval(d.tenant(), d.disruptionId());
    List<StatusChange> history =
        disruptions.history(d.tenant(), d.disruptionId()).stream()
            .map(h -> new StatusChange(h[0], h[1], h[2], h[3]))
            .toList();
    return new DisruptionView(
        d.disruptionId(),
        d.tenant().value(),
        d.tripId(),
        d.orderId(),
        d.travelerId(),
        d.segmentId(),
        d.type(),
        d.supplier(),
        d.supplierEventId(),
        d.externalOrderId(),
        d.recordLocator(),
        d.detectedAt(),
        d.status().name(),
        d.severity(),
        d.rawReference(),
        d.reason(),
        JSON.readTree(d.affectedJson()),
        JSON.readTree(d.recoveryJson()),
        d.failureStage(),
        d.failureCode(),
        decision.map(JSON::readTree).orElse(null),
        outcome.map(JSON::readTree).orElse(null),
        approval.map(ApprovalView::from).orElse(null),
        history,
        d.version(),
        d.createdAt(),
        d.updatedAt());
  }
}
