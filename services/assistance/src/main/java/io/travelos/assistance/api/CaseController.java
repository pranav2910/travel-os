package io.travelos.assistance.api;

import io.travelos.assistance.model.AssistanceCase;
import io.travelos.assistance.model.CaseEvent;
import io.travelos.assistance.model.CaseKind;
import io.travelos.assistance.model.CaseStatus;
import io.travelos.assistance.model.Priority;
import io.travelos.assistance.model.Queue;
import io.travelos.assistance.service.CaseService;
import io.travelos.assistance.store.CaseRepository;
import io.travelos.spring.web.auth.RequestPrincipal;
import io.travelos.spring.web.error.ApiException;
import io.travelos.spring.web.idempotency.IdempotencyKeyHeader;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Cases for the people who work them (TRAVEL_ADMIN, FINANCE) and for travelers on their own trips.
 * Tenant-scoped: another tenant's case, or another traveler's, is 404, never 403.
 */
@RestController
@RequestMapping(path = "/api/v1/cases", produces = "application/json")
public class CaseController {
  private final CaseService cases;

  public CaseController(CaseService cases) {
    this.cases = cases;
  }

  public record CaseView(
      String caseId,
      String kind,
      String status,
      String priority,
      String queue,
      String title,
      @Nullable String summary,
      @Nullable String tripId,
      @Nullable String orderId,
      @Nullable String travelerId,
      @Nullable String disruptionId,
      @Nullable String exposureId,
      @Nullable String componentId,
      @Nullable String owner,
      String nextAction,
      String nextActionRole,
      int escalationLevel,
      Instant dueAt,
      boolean overdue,
      Instant openedAt,
      Instant updatedAt,
      @Nullable Instant resolvedAt,
      @Nullable Instant closedAt,
      @Nullable String resolution,
      @Nullable String sourceEventType,
      long version) {
    static CaseView from(AssistanceCase c, Instant now) {
      return new CaseView(
          c.caseId(),
          c.kind().name(),
          c.status().name(),
          c.priority().name(),
          c.queue().name(),
          c.title(),
          c.summary(),
          c.tripId(),
          c.orderId(),
          c.travelerId(),
          c.disruptionId(),
          c.exposureId(),
          c.componentId(),
          c.owner(),
          c.nextAction(),
          c.nextActionRole(),
          c.escalationLevel(),
          c.dueAt(),
          c.overdue(now),
          c.openedAt(),
          c.updatedAt(),
          c.resolvedAt(),
          c.closedAt(),
          c.resolution(),
          c.sourceEventType(),
          c.version());
    }
  }

  public record EventView(
      String caseEventId,
      String kind,
      String actor,
      String message,
      Map<String, Object> data,
      Instant occurredAt) {
    static EventView from(CaseEvent e) {
      return new EventView(
          e.caseEventId(), e.kind().name(), e.actor(), e.message(), e.data(), e.occurredAt());
    }
  }

  public record OpenRequest(
      @NotNull CaseKind kind,
      @Nullable Priority priority,
      @NotBlank @Size(max = 300) String title,
      @Nullable @Size(max = 4000) String summary,
      @Nullable @Size(max = 64) String tripId,
      @Nullable @Size(max = 64) String orderId) {}

  public record AssignRequest(@NotBlank @Size(max = 200) String owner) {}

  public record NoteRequest(@NotBlank @Size(max = 4000) String text) {}

  public record StatusRequest(
      @NotNull CaseStatus status, @Nullable @Size(max = 1000) String reason) {}

  public record ReasonRequest(@NotBlank @Size(max = 2000) String reason) {}

  public record ResolutionRequest(@NotBlank @Size(max = 4000) String resolution) {}

  public record ClosureRequest(@Nullable @Size(max = 1000) String reason) {}

  @GetMapping
  public List<CaseView> list(
      @AuthenticationPrincipal RequestPrincipal me,
      @RequestParam(required = false) @Nullable String status,
      @RequestParam(required = false) @Nullable String queue,
      @RequestParam(required = false) @Nullable String kind,
      @RequestParam(required = false) @Nullable String owner,
      @RequestParam(required = false) @Nullable String tripId,
      @RequestParam(defaultValue = "false") boolean overdue,
      @RequestParam(defaultValue = "false") boolean includeClosed,
      @RequestParam(defaultValue = "100") int limit) {
    CaseRepository.Filter f =
        new CaseRepository.Filter(
            status == null ? null : parse(CaseStatus.class, status, "status"),
            !includeClosed,
            queue == null ? null : parse(Queue.class, queue, "queue"),
            kind == null ? null : parse(CaseKind.class, kind, "kind"),
            "me".equals(owner) ? me.principal().id() : owner,
            tripId,
            null,
            overdue,
            Math.clamp(limit, 1, 500));
    Instant now = Instant.now();
    return cases.list(me, f).stream().map(c -> CaseView.from(c, now)).toList();
  }

  /** Counts per queue and status, with how many are overdue: the board's header. */
  @GetMapping("/summary")
  public Map<String, Object> summary(@AuthenticationPrincipal RequestPrincipal me) {
    Map<String, Object> out = new LinkedHashMap<>();
    long open = 0;
    long overdue = 0;
    Map<String, Map<String, Long>> byQueue = new LinkedHashMap<>();
    for (CaseRepository.Summary s : cases.summary(me)) {
      byQueue.computeIfAbsent(s.queue(), q -> new LinkedHashMap<>()).put(s.status(), s.count());
      if (CaseStatus.valueOf(s.status()).open()) {
        open += s.count();
        overdue += s.overdue();
      }
    }
    out.put("open", open);
    out.put("overdue", overdue);
    out.put("byQueue", byQueue);
    return out;
  }

  @GetMapping("/{caseId}")
  public CaseView get(@AuthenticationPrincipal RequestPrincipal me, @PathVariable String caseId) {
    return CaseView.from(cases.get(me, caseId), Instant.now());
  }

  @GetMapping("/{caseId}/events")
  public List<EventView> events(
      @AuthenticationPrincipal RequestPrincipal me, @PathVariable String caseId) {
    return cases.history(me, caseId).stream().map(EventView::from).toList();
  }

  @PostMapping(consumes = "application/json")
  public ResponseEntity<CaseView> open(
      @AuthenticationPrincipal RequestPrincipal me,
      @IdempotencyKeyHeader String idempotencyKey,
      @Valid @RequestBody OpenRequest request) {
    AssistanceCase c =
        cases.openManually(
            me,
            request.kind(),
            request.priority(),
            request.title(),
            request.summary(),
            request.tripId(),
            request.orderId(),
            idempotencyKey);
    return ResponseEntity.status(HttpStatus.CREATED).body(CaseView.from(c, Instant.now()));
  }

  @PostMapping(path = "/{caseId}/assignment", consumes = "application/json")
  public CaseView assign(
      @AuthenticationPrincipal RequestPrincipal me,
      @PathVariable String caseId,
      @IdempotencyKeyHeader String idempotencyKey,
      @Valid @RequestBody AssignRequest request) {
    return CaseView.from(cases.assign(me, caseId, request.owner()), Instant.now());
  }

  @PostMapping(path = "/{caseId}/notes", consumes = "application/json")
  public ResponseEntity<CaseView> note(
      @AuthenticationPrincipal RequestPrincipal me,
      @PathVariable String caseId,
      @IdempotencyKeyHeader String idempotencyKey,
      @Valid @RequestBody NoteRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(CaseView.from(cases.note(me, caseId, request.text()), Instant.now()));
  }

  @PutMapping(path = "/{caseId}/status", consumes = "application/json")
  public CaseView status(
      @AuthenticationPrincipal RequestPrincipal me,
      @PathVariable String caseId,
      @Valid @RequestBody StatusRequest request) {
    return CaseView.from(
        cases.setStatus(me, caseId, request.status(), request.reason()), Instant.now());
  }

  @PostMapping(path = "/{caseId}/escalation", consumes = "application/json")
  public CaseView escalate(
      @AuthenticationPrincipal RequestPrincipal me,
      @PathVariable String caseId,
      @IdempotencyKeyHeader String idempotencyKey,
      @Valid @RequestBody ReasonRequest request) {
    return CaseView.from(cases.escalate(me, caseId, request.reason()), Instant.now());
  }

  @PostMapping(path = "/{caseId}/resolution", consumes = "application/json")
  public CaseView resolve(
      @AuthenticationPrincipal RequestPrincipal me,
      @PathVariable String caseId,
      @IdempotencyKeyHeader String idempotencyKey,
      @Valid @RequestBody ResolutionRequest request) {
    return CaseView.from(cases.resolve(me, caseId, request.resolution()), Instant.now());
  }

  @PostMapping(path = "/{caseId}/closure", consumes = "application/json")
  public CaseView close(
      @AuthenticationPrincipal RequestPrincipal me,
      @PathVariable String caseId,
      @IdempotencyKeyHeader String idempotencyKey,
      @RequestBody(required = false) @Nullable ClosureRequest request) {
    return CaseView.from(
        cases.close(me, caseId, request == null ? null : request.reason()), Instant.now());
  }

  private static <E extends Enum<E>> E parse(Class<E> type, String value, String name) {
    try {
      return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new ApiException.Unprocessable(
          name.toUpperCase(Locale.ROOT) + "_UNKNOWN",
          name + " must be one of " + java.util.Arrays.toString(type.getEnumConstants()));
    }
  }
}
