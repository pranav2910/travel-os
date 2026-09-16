package io.travelos.context.api;

import io.travelos.context.model.DemandCandidate;
import io.travelos.context.model.DemandStatus;
import io.travelos.context.model.DemandTransition;
import io.travelos.context.model.SourceRef;
import io.travelos.context.service.DemandService;
import io.travelos.spring.web.auth.RequestPrincipal;
import io.travelos.spring.web.error.ApiException;
import io.travelos.spring.web.idempotency.IdempotencyKeyHeader;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
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
 * Travel demand for people: what the platform noticed, why, on what evidence, what is missing; and
 * the three things a person may do about it (supply details, dismiss, convert into a trip).
 */
@RestController
@RequestMapping(path = "/api/v1/demand", produces = "application/json")
public class DemandController {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private final DemandService demand;

  public DemandController(DemandService demand) {
    this.demand = demand;
  }

  public record CandidateView(
      String candidateId,
      String tenantId,
      String travelerId,
      String status,
      @Nullable String origin,
      @Nullable String destination,
      @Nullable LocalDate startDate,
      @Nullable LocalDate endDate,
      @Nullable String timeZone,
      @Nullable Instant windowStart,
      @Nullable Instant windowEnd,
      @Nullable String purpose,
      List<String> missing,
      List<String> reviewReasons,
      List<Map<String, Object>> sources,
      String rulesVersion,
      String explanation,
      @Nullable String tripId,
      long version,
      Instant createdAt,
      Instant updatedAt) {
    static CandidateView from(DemandCandidate c) {
      return new CandidateView(
          c.candidateId(),
          c.tenant().value(),
          c.travelerId(),
          c.status().name(),
          c.origin(),
          c.destination(),
          c.startDate(),
          c.endDate(),
          c.timeZone(),
          c.windowStart(),
          c.windowEnd(),
          c.purpose(),
          c.missing(),
          c.reviewReasons(),
          c.sources().stream().map(SourceRef::toMap).toList(),
          c.rulesVersion(),
          c.explanation(),
          c.tripId(),
          c.version(),
          c.createdAt(),
          c.updatedAt());
    }
  }

  public record TransitionView(
      @Nullable String from,
      String to,
      String reason,
      @Nullable String detail,
      String actor,
      Instant occurredAt) {
    static TransitionView from(DemandTransition t) {
      return new TransitionView(
          t.from() == null ? null : t.from().name(),
          t.to().name(),
          t.reason(),
          t.detail(),
          t.actor(),
          t.occurredAt());
    }
  }

  public record RevisionView(
      long revision, String status, JsonNode item, @Nullable String runId, Instant observedAt) {}

  public record EvidenceView(
      String connectorId,
      String kind,
      String sourceId,
      long revision,
      String status,
      JsonNode item,
      Instant firstSeenAt,
      Instant observedAt,
      List<RevisionView> revisions) {}

  @GetMapping
  public List<CandidateView> list(
      @AuthenticationPrincipal RequestPrincipal me,
      @RequestParam(required = false) @Nullable String travelerId,
      @RequestParam(required = false) @Nullable String status) {
    DemandStatus s = null;
    if (status != null && !status.isBlank()) {
      try {
        s = DemandStatus.valueOf(status);
      } catch (IllegalArgumentException e) {
        throw new ApiException.Unprocessable("STATUS_UNKNOWN", "unknown status " + status);
      }
    }
    return demand.list(me, travelerId, s).stream().map(CandidateView::from).toList();
  }

  @GetMapping("/{candidateId}")
  public CandidateView get(
      @AuthenticationPrincipal RequestPrincipal me, @PathVariable String candidateId) {
    return CandidateView.from(demand.get(me, candidateId));
  }

  @GetMapping("/{candidateId}/history")
  public List<TransitionView> history(
      @AuthenticationPrincipal RequestPrincipal me, @PathVariable String candidateId) {
    return demand.history(me, candidateId).stream().map(TransitionView::from).toList();
  }

  @GetMapping("/{candidateId}/evidence")
  public List<EvidenceView> evidence(
      @AuthenticationPrincipal RequestPrincipal me, @PathVariable String candidateId) {
    return demand.evidence(me, candidateId).stream()
        .map(
            e ->
                new EvidenceView(
                    e.item().connectorId(),
                    e.item().kind().name(),
                    e.item().sourceId(),
                    e.item().revision(),
                    e.item().status().name(),
                    JSON.readTree(e.item().normalizedJson()),
                    e.item().firstSeenAt(),
                    e.item().observedAt(),
                    e.revisions().stream()
                        .map(
                            r ->
                                new RevisionView(
                                    r.revision(),
                                    r.status(),
                                    JSON.readTree(r.normalizedJson()),
                                    r.runId(),
                                    r.observedAt()))
                        .toList()))
        .toList();
  }

  public record DetailsRequest(
      @Nullable @Pattern(regexp = "^[A-Z]{3}$") String destination,
      @Nullable LocalDate startDate,
      @Nullable LocalDate endDate,
      @Nullable @Size(max = 200) String clearReviewFlag) {}

  @PostMapping(path = "/{candidateId}/details", consumes = "application/json")
  public CandidateView details(
      @AuthenticationPrincipal RequestPrincipal me,
      @PathVariable String candidateId,
      @IdempotencyKeyHeader String idempotencyKey,
      @Valid @RequestBody DetailsRequest request) {
    if (request.clearReviewFlag() != null && !request.clearReviewFlag().isBlank()) {
      DemandCandidate cleared = demand.clearReview(me, candidateId, request.clearReviewFlag());
      if (request.destination() == null
          && request.startDate() == null
          && request.endDate() == null) {
        return CandidateView.from(cleared);
      }
    }
    return CandidateView.from(
        demand.resolveDetails(
            me,
            candidateId,
            request.destination(),
            request.startDate(),
            request.endDate(),
            idempotencyKey));
  }

  public record DismissalRequest(@Nullable @Size(max = 2000) String reason) {}

  @PostMapping(path = "/{candidateId}/dismissal", consumes = "application/json")
  public CandidateView dismiss(
      @AuthenticationPrincipal RequestPrincipal me,
      @PathVariable String candidateId,
      @IdempotencyKeyHeader String idempotencyKey,
      @Valid @RequestBody DismissalRequest request) {
    return CandidateView.from(demand.dismiss(me, candidateId, request.reason(), idempotencyKey));
  }

  public record ConversionView(String candidateId, String status, String tripId) {}

  /** Actionable demand becomes a trip request: the governed trip lifecycle takes over. */
  @PostMapping(path = "/{candidateId}/conversion")
  public ConversionView convert(
      @AuthenticationPrincipal RequestPrincipal me,
      @PathVariable String candidateId,
      @IdempotencyKeyHeader String idempotencyKey) {
    DemandCandidate c = demand.convert(me, candidateId, idempotencyKey);
    return new ConversionView(c.candidateId(), c.status().name(), c.tripId());
  }
}
