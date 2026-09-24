package io.travelos.assistance.service;

import io.travelos.assistance.AssistanceProperties;
import io.travelos.assistance.events.AssistanceEvents;
import io.travelos.assistance.metrics.AssistanceMetrics;
import io.travelos.assistance.model.AssistanceCase;
import io.travelos.assistance.model.CaseEvent;
import io.travelos.assistance.model.CaseKind;
import io.travelos.assistance.model.CaseStatus;
import io.travelos.assistance.model.Priority;
import io.travelos.assistance.model.Queue;
import io.travelos.assistance.store.CaseRepository;
import io.travelos.assistance.store.TripIndexRepository;
import io.travelos.common.ids.IdPrefix;
import io.travelos.common.ids.Ids;
import io.travelos.common.tenant.TenantId;
import io.travelos.spring.outbox.Outbox;
import io.travelos.spring.web.auth.RequestPrincipal;
import io.travelos.spring.web.error.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The case lifecycle. Every change is one transaction: the row, its history entry and the outbox
 * event commit together or not at all. Nothing here moves money, touches a supplier, or changes a
 * trip: a case points a person at the authoritative service that does.
 */
@Service
public class CaseService {
  private static final Logger log = LoggerFactory.getLogger(CaseService.class);
  static final String SYSTEM = "service/assistance";

  /** What a case is opened with; the dedupe key makes the same fact one case. */
  public record OpenCase(
      TenantId tenant,
      CaseKind kind,
      Priority priority,
      Queue queue,
      String title,
      @Nullable String summary,
      @Nullable String tripId,
      @Nullable String orderId,
      @Nullable String travelerId,
      @Nullable String disruptionId,
      @Nullable String exposureId,
      @Nullable String componentId,
      String dedupeKey,
      String nextAction,
      @Nullable String nextActionRole,
      @Nullable String sourceEventId,
      @Nullable String sourceEventType,
      String openedBy) {}

  private final CaseRepository cases;
  private final TripIndexRepository trips;
  private final Outbox outbox;
  private final AssistanceMetrics metrics;
  private final AssistanceProperties properties;
  private final Clock clock;

  public CaseService(
      CaseRepository cases,
      TripIndexRepository trips,
      Outbox outbox,
      AssistanceMetrics metrics,
      AssistanceProperties properties,
      Clock clock) {
    this.cases = cases;
    this.trips = trips;
    this.outbox = outbox;
    this.metrics = metrics;
    this.properties = properties;
    this.clock = clock;
  }

  // ------------------------------------------------------------------ opening and settling

  /** Opens a case, or links the fact to the open case that already covers it. */
  @Transactional
  public AssistanceCase open(OpenCase cmd) {
    Instant now = clock.instant();
    Optional<AssistanceCase> existing = cases.findOpen(cmd.tenant(), cmd.dedupeKey());
    if (existing.isPresent()) {
      AssistanceCase c = existing.get();
      record(
          c,
          CaseEvent.Kind.LINKED,
          cmd.openedBy(),
          "also reported by " + describe(cmd),
          Map.of("sourceEventId", str(cmd.sourceEventId())),
          now);
      metrics.caseOutcome(cmd.kind().name(), cmd.queue().name(), "LINKED");
      return c;
    }
    String travelerId = cmd.travelerId();
    if (travelerId == null && cmd.tripId() != null) {
      travelerId =
          trips
              .find(cmd.tenant(), cmd.tripId())
              .map(TripIndexRepository.TripRef::travelerId)
              .orElse(null);
    }
    AssistanceCase c =
        new AssistanceCase(
            Ids.newId(IdPrefix.ASSISTANCE_CASE),
            cmd.tenant(),
            cmd.kind(),
            CaseStatus.OPEN,
            cmd.priority(),
            cmd.queue(),
            cmd.title(),
            cmd.summary(),
            cmd.tripId(),
            cmd.orderId(),
            travelerId,
            cmd.disruptionId(),
            cmd.exposureId(),
            cmd.componentId(),
            cmd.dedupeKey(),
            null,
            cmd.nextAction(),
            cmd.nextActionRole() == null ? CaseAccess.roleFor(cmd.queue()) : cmd.nextActionRole(),
            0,
            now.plus(properties.slaFor(cmd.priority())),
            now,
            now,
            null,
            null,
            null,
            cmd.sourceEventId(),
            cmd.sourceEventType(),
            0);
    cases.insert(c);
    record(
        c,
        CaseEvent.Kind.OPENED,
        cmd.openedBy(),
        describe(cmd),
        Map.of("sourceEventId", str(cmd.sourceEventId())),
        now);
    outbox.append(AssistanceEvents.opened(c, clock));
    metrics.caseOutcome(cmd.kind().name(), cmd.queue().name(), "OPENED");
    log.info("case {} opened: {} {} ({})", c.caseId(), c.kind(), c.title(), c.dedupeKey());
    return c;
  }

  /** The platform reported the fact settled: the open case (if any) is resolved by the system. */
  @Transactional
  public Optional<AssistanceCase> settle(
      TenantId tenant, String dedupeKey, String resolution, String by, @Nullable String eventId) {
    return cases.findOpen(tenant, dedupeKey).map(c -> resolveInternal(c, resolution, by, eventId));
  }

  /** Settles every open case whose key starts with the prefix (all of a trip's, a disruption's). */
  @Transactional
  public List<AssistanceCase> settleAll(
      TenantId tenant, String prefix, String resolution, String by, @Nullable String eventId) {
    return cases.findOpenByPrefix(tenant, prefix).stream()
        .map(c -> resolveInternal(c, resolution, by, eventId))
        .toList();
  }

  // ------------------------------------------------------------------ reading

  @Transactional(readOnly = true)
  public AssistanceCase get(RequestPrincipal me, String caseId) {
    AssistanceCase c =
        cases
            .find(me.tenant(), caseId)
            .orElseThrow(() -> new ApiException.NotFound("case", caseId));
    if (!CaseAccess.canRead(me, c)) {
      throw new ApiException.NotFound("case", caseId);
    }
    return c;
  }

  @Transactional(readOnly = true)
  public List<AssistanceCase> list(RequestPrincipal me, CaseRepository.Filter filter) {
    if (!CaseAccess.worker(me)) {
      if (me.employeeId() == null) {
        return List.of();
      }
      filter =
          new CaseRepository.Filter(
              filter.status(),
              filter.openOnly(),
              filter.queue(),
              filter.kind(),
              filter.owner(),
              filter.tripId(),
              me.employeeId(),
              filter.overdueOnly(),
              filter.limit());
    }
    return cases.list(me.tenant(), filter, clock.instant());
  }

  @Transactional(readOnly = true)
  public List<CaseEvent> history(RequestPrincipal me, String caseId) {
    get(me, caseId);
    return cases.events(me.tenant(), caseId);
  }

  @Transactional(readOnly = true)
  public List<CaseRepository.Summary> summary(RequestPrincipal me) {
    if (!CaseAccess.worker(me)) {
      throw new ApiException.Forbidden(
          "NOT_A_CASE_WORKER", "TRAVEL_ADMIN or FINANCE role required");
    }
    return cases.summary(me.tenant(), clock.instant());
  }

  // ------------------------------------------------------------------ people acting

  /** A person opens a case themselves (a traveler on their own trip, a worker on anything). */
  @Transactional
  public AssistanceCase openManually(
      RequestPrincipal me,
      CaseKind kind,
      @Nullable Priority priority,
      String title,
      @Nullable String summary,
      @Nullable String tripId,
      @Nullable String orderId,
      String idempotencyKey) {
    if (!CaseAccess.worker(me) && me.employeeId() == null) {
      throw new ApiException.Forbidden("NOT_ALLOWED", "no employee identity");
    }
    Queue queue =
        kind == CaseKind.SAFETY
            ? Queue.SAFETY
            : kind == CaseKind.EXPOSURE || kind == CaseKind.PAYMENT_DECLINED
                ? Queue.FINANCE
                : Queue.TRAVEL_OPS;
    String travelerId = CaseAccess.worker(me) ? null : me.employeeId();
    if (!CaseAccess.worker(me) && tripId != null) {
      // a traveler opens cases on their own trips only; a trip we never heard of is refused
      String owner =
          trips.find(me.tenant(), tripId).map(TripIndexRepository.TripRef::travelerId).orElse(null);
      if (owner == null || !owner.equals(me.employeeId())) {
        throw new ApiException.NotFound("trip", tripId);
      }
    }
    Priority p =
        priority != null ? priority : kind == CaseKind.SAFETY ? Priority.CRITICAL : Priority.NORMAL;
    return open(
        new OpenCase(
            me.tenant(),
            kind,
            p,
            queue,
            title,
            summary,
            tripId,
            orderId,
            travelerId,
            null,
            null,
            null,
            "manual:" + me.principal().id() + ":" + idempotencyKey,
            kind == CaseKind.SAFETY
                ? "Reach the traveler and confirm they are safe; record the outcome"
                : "Read the request and reply to the traveler; record what was done",
            null,
            null,
            null,
            me.principal().id()));
  }

  @Transactional
  public AssistanceCase assign(RequestPrincipal me, String caseId, String owner) {
    AssistanceCase c = lockForAction(me, caseId, "ASSIGN");
    if (!c.status().open()) {
      metrics.action("ASSIGN", "REFUSED");
      throw new ApiException.Conflict("CASE_NOT_OPEN", "case " + caseId + " is " + c.status());
    }
    String to = "me".equals(owner) ? me.principal().id() : owner;
    Instant now = clock.instant();
    CaseStatus status =
        c.status() == CaseStatus.OPEN || c.status() == CaseStatus.ESCALATED
            ? CaseStatus.IN_PROGRESS
            : c.status();
    AssistanceCase next =
        with(
            c,
            status,
            c.priority(),
            c.queue(),
            to,
            c.nextAction(),
            c.nextActionRole(),
            c.escalationLevel(),
            c.dueAt(),
            now,
            c.resolvedAt(),
            c.closedAt(),
            c.resolution());
    save(next, c.version());
    record(
        next,
        CaseEvent.Kind.ASSIGNED,
        me.principal().id(),
        "assigned to " + to,
        Map.of("owner", to),
        now);
    outbox.append(AssistanceEvents.assigned(next, me.principal().id(), clock));
    metrics.action("ASSIGN", "OK");
    return next;
  }

  @Transactional
  public AssistanceCase note(RequestPrincipal me, String caseId, String text) {
    AssistanceCase c =
        cases
            .lock(me.tenant(), caseId)
            .orElseThrow(() -> new ApiException.NotFound("case", caseId));
    if (!CaseAccess.canRead(me, c)) {
      throw new ApiException.NotFound("case", caseId);
    }
    Instant now = clock.instant();
    record(c, CaseEvent.Kind.NOTE, me.principal().id(), text, Map.of(), now);
    metrics.action("NOTE", "OK");
    return c;
  }

  @Transactional
  public AssistanceCase setStatus(
      RequestPrincipal me, String caseId, CaseStatus to, @Nullable String reason) {
    AssistanceCase c = lockForAction(me, caseId, "STATUS");
    if (to != CaseStatus.IN_PROGRESS && to != CaseStatus.WAITING && to != CaseStatus.OPEN) {
      metrics.action("STATUS", "REFUSED");
      throw new ApiException.Unprocessable(
          "STATUS_NOT_SETTABLE", "use the resolution, escalation and closure endpoints for " + to);
    }
    if (c.status() == to) {
      return c;
    }
    if (!c.status().canMoveTo(to)) {
      metrics.action("STATUS", "REFUSED");
      throw new ApiException.Conflict(
          "STATUS_TRANSITION_INVALID", c.status() + " -> " + to + " is not allowed");
    }
    Instant now = clock.instant();
    AssistanceCase next =
        with(
            c,
            to,
            c.priority(),
            c.queue(),
            c.owner(),
            c.nextAction(),
            c.nextActionRole(),
            c.escalationLevel(),
            c.dueAt(),
            now,
            to == CaseStatus.OPEN ? null : c.resolvedAt(),
            c.closedAt(),
            to == CaseStatus.OPEN ? null : c.resolution());
    save(next, c.version());
    record(
        next,
        to == CaseStatus.OPEN ? CaseEvent.Kind.REOPENED : CaseEvent.Kind.STATUS,
        me.principal().id(),
        c.status() + " -> " + to + (reason == null ? "" : ": " + reason),
        Map.of("from", c.status().name(), "to", to.name()),
        now);
    metrics.action("STATUS", "OK");
    return next;
  }

  @Transactional
  public AssistanceCase escalate(RequestPrincipal me, String caseId, String reason) {
    AssistanceCase c = lockForAction(me, caseId, "ESCALATE");
    if (!c.status().open()) {
      metrics.action("ESCALATE", "REFUSED");
      throw new ApiException.Conflict("CASE_NOT_OPEN", "case " + caseId + " is " + c.status());
    }
    AssistanceCase next = escalated(c, me.principal().id(), reason, clock.instant());
    metrics.action("ESCALATE", "OK");
    return next;
  }

  @Transactional
  public AssistanceCase resolve(RequestPrincipal me, String caseId, String resolution) {
    AssistanceCase c = lockForAction(me, caseId, "RESOLVE");
    if (c.status() == CaseStatus.RESOLVED) {
      return c;
    }
    if (!c.status().open()) {
      metrics.action("RESOLVE", "REFUSED");
      throw new ApiException.Conflict("CASE_NOT_OPEN", "case " + caseId + " is " + c.status());
    }
    AssistanceCase next = resolveInternal(c, resolution, me.principal().id(), null);
    metrics.action("RESOLVE", "OK");
    return next;
  }

  @Transactional
  public AssistanceCase close(RequestPrincipal me, String caseId, @Nullable String reason) {
    AssistanceCase c = lockForAction(me, caseId, "CLOSE");
    if (c.status() == CaseStatus.CLOSED) {
      return c;
    }
    if (c.status() != CaseStatus.RESOLVED) {
      metrics.action("CLOSE", "REFUSED");
      throw new ApiException.Conflict(
          "CASE_NOT_RESOLVED",
          "resolve case " + caseId + " before closing it (it is " + c.status() + ")");
    }
    Instant now = clock.instant();
    AssistanceCase next =
        with(
            c,
            CaseStatus.CLOSED,
            c.priority(),
            c.queue(),
            c.owner(),
            "none",
            c.nextActionRole(),
            c.escalationLevel(),
            c.dueAt(),
            now,
            c.resolvedAt(),
            now,
            c.resolution());
    save(next, c.version());
    record(
        next,
        CaseEvent.Kind.CLOSED,
        me.principal().id(),
        reason == null ? "closed" : reason,
        Map.of(),
        now);
    outbox.append(AssistanceEvents.closed(next, me.principal().id(), clock));
    metrics.action("CLOSE", "OK");
    return next;
  }

  // ------------------------------------------------------------------ the sweep

  /** Overdue cases escalate one level: more urgent, a wider audience, a fresh due time. */
  @Scheduled(fixedDelayString = "${travelos.assistance.escalation-sweep:1m}")
  @Transactional
  public int escalateOverdue() {
    Instant now = clock.instant();
    int n = 0;
    for (AssistanceCase c : cases.overdue(now, 200)) {
      if (c.escalationLevel() >= properties.maxEscalationLevel()) {
        continue; // as high as it goes; the people at level 3 own it until they resolve it
      }
      escalated(c, SYSTEM, "overdue: due " + c.dueAt() + ", not resolved by " + now, now);
      n++;
    }
    if (n > 0) {
      log.warn("escalated {} overdue case(s)", n);
    }
    return n;
  }

  // ------------------------------------------------------------------ internals

  private AssistanceCase escalated(AssistanceCase c, String by, String reason, Instant now) {
    int level = Math.min(c.escalationLevel() + 1, properties.maxEscalationLevel());
    Priority priority = c.priority().raised();
    String role =
        level >= 3 ? "TRAVEL_ADMIN+FINANCE" : level == 2 ? "TRAVEL_ADMIN" : c.nextActionRole();
    AssistanceCase next =
        with(
            c,
            CaseStatus.ESCALATED,
            priority,
            c.queue(),
            c.owner(),
            c.nextAction(),
            role,
            level,
            now.plus(properties.slaFor(priority)),
            now,
            c.resolvedAt(),
            c.closedAt(),
            c.resolution());
    save(next, c.version());
    record(
        next,
        CaseEvent.Kind.ESCALATED,
        by,
        "escalated to level " + level + ": " + reason,
        Map.of("level", level, "priority", priority.name()),
        now);
    outbox.append(AssistanceEvents.escalated(next, by, reason, clock));
    metrics.escalation(level);
    return next;
  }

  private AssistanceCase resolveInternal(
      AssistanceCase c, String resolution, String by, @Nullable String eventId) {
    Instant now = clock.instant();
    AssistanceCase next =
        with(
            c,
            CaseStatus.RESOLVED,
            c.priority(),
            c.queue(),
            c.owner(),
            "close the case once the traveler is told",
            c.nextActionRole(),
            c.escalationLevel(),
            c.dueAt(),
            now,
            now,
            c.closedAt(),
            resolution);
    save(next, c.version());
    record(
        next,
        CaseEvent.Kind.RESOLVED,
        by,
        resolution,
        eventId == null ? Map.of() : Map.of("sourceEventId", eventId),
        now);
    outbox.append(AssistanceEvents.resolved(next, by, clock));
    if (SYSTEM.equals(by) || by.startsWith("service/") || by.startsWith("agent/")) {
      metrics.caseOutcome(c.kind().name(), c.queue().name(), "AUTO_RESOLVED");
    }
    return next;
  }

  private AssistanceCase lockForAction(RequestPrincipal me, String caseId, String action) {
    AssistanceCase c =
        cases
            .lock(me.tenant(), caseId)
            .orElseThrow(() -> new ApiException.NotFound("case", caseId));
    if (!CaseAccess.canRead(me, c)) {
      throw new ApiException.NotFound("case", caseId);
    }
    if (!CaseAccess.canAct(me, c)) {
      metrics.action(action, "REFUSED");
      throw new ApiException.Forbidden(
          "NOT_A_CASE_WORKER", "TRAVEL_ADMIN or FINANCE role required");
    }
    return c;
  }

  private void save(AssistanceCase next, long expectedVersion) {
    if (!cases.update(next, expectedVersion)) {
      throw new ApiException.Conflict(
          "CASE_CHANGED", "case " + next.caseId() + " changed underneath; read it again");
    }
  }

  private void record(
      AssistanceCase c,
      CaseEvent.Kind kind,
      String actor,
      String message,
      Map<String, Object> data,
      Instant now) {
    cases.appendEvent(
        c.tenant(),
        new CaseEvent(Ids.newId(IdPrefix.CASE_EVENT), c.caseId(), kind, actor, message, data, now));
  }

  private static AssistanceCase with(
      AssistanceCase c,
      CaseStatus status,
      Priority priority,
      Queue queue,
      @Nullable String owner,
      String nextAction,
      String role,
      int level,
      Instant dueAt,
      Instant updatedAt,
      @Nullable Instant resolvedAt,
      @Nullable Instant closedAt,
      @Nullable String resolution) {
    return new AssistanceCase(
        c.caseId(),
        c.tenant(),
        c.kind(),
        status,
        priority,
        queue,
        c.title(),
        c.summary(),
        c.tripId(),
        c.orderId(),
        c.travelerId(),
        c.disruptionId(),
        c.exposureId(),
        c.componentId(),
        c.dedupeKey(),
        owner,
        nextAction,
        role,
        level,
        dueAt,
        c.openedAt(),
        updatedAt,
        resolvedAt,
        closedAt,
        resolution,
        c.sourceEventId(),
        c.sourceEventType(),
        c.version() + 1);
  }

  private static String describe(OpenCase cmd) {
    return cmd.sourceEventType() == null ? cmd.title() : cmd.sourceEventType() + ": " + cmd.title();
  }

  private static String str(@Nullable String s) {
    return s == null ? "" : s;
  }
}
