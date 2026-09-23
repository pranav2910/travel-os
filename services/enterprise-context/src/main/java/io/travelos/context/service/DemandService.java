package io.travelos.context.service;

import io.travelos.common.identity.Principal;
import io.travelos.common.ids.IdPrefix;
import io.travelos.common.ids.Ids;
import io.travelos.common.tenant.TenantId;
import io.travelos.context.detect.DetectionRules;
import io.travelos.context.detect.TravelPlan;
import io.travelos.context.events.DemandEvents;
import io.travelos.context.metrics.DemandMetrics;
import io.travelos.context.model.Connector;
import io.travelos.context.model.ConnectorKind;
import io.travelos.context.model.DemandCandidate;
import io.travelos.context.model.DemandStatus;
import io.travelos.context.model.DemandTransition;
import io.travelos.context.model.Employee;
import io.travelos.context.model.SourceItem;
import io.travelos.context.model.SourceRef;
import io.travelos.context.source.CalendarEvent;
import io.travelos.context.source.CrmRecord;
import io.travelos.context.source.ExpenseRecord;
import io.travelos.context.source.HrisRecord;
import io.travelos.context.source.SourcePage;
import io.travelos.context.store.DemandRepository;
import io.travelos.context.store.EmployeeRepository;
import io.travelos.context.store.SourceItemRepository;
import io.travelos.contracts.trip.v1.TravelIntent;
import io.travelos.contracts.trip.v1.Trip;
import io.travelos.spring.outbox.Outbox;
import io.travelos.spring.web.auth.RequestPrincipal;
import io.travelos.spring.web.error.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Travel-demand candidates: created and kept current by source ingestion (deterministic rules,
 * correlation across sources, enrichment), acted on by people (details, dismissal, conversion).
 * Detection never books; conversion hands a frozen intent to Travel Core, once.
 */
@Service
public class DemandService {
  private static final Logger log = LoggerFactory.getLogger(DemandService.class);
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Principal DETECTOR = Principal.parse("service/enterprise-context");

  private final DemandRepository demand;
  private final SourceItemRepository items;
  private final EmployeeRepository employees;
  private final TripClient trips;
  private final Outbox outbox;
  private final DemandMetrics metrics;
  private final Clock clock;

  public DemandService(
      DemandRepository demand,
      SourceItemRepository items,
      EmployeeRepository employees,
      TripClient trips,
      Outbox outbox,
      DemandMetrics metrics,
      Clock clock) {
    this.demand = demand;
    this.items = items;
    this.employees = employees;
    this.trips = trips;
    this.outbox = outbox;
    this.metrics = metrics;
    this.clock = clock;
  }

  /** What one page entry did: was the item new or changed, and how many candidates it touched. */
  public record Ingested(boolean changed, int candidatesTouched) {
    static final Ingested NOTHING = new Ingested(false, 0);
  }

  // ================================================================== ingestion (inside
  // SyncService's transaction)

  public Ingested ingest(Connector connector, SourcePage.Entry<?> entry, @Nullable String runId) {
    Instant now = clock.instant();
    TenantId tenant = connector.tenant();
    Optional<SourceItem> previous = items.find(tenant, connector.connectorId(), entry.sourceId());
    if (previous.isPresent() && entry.revision() <= previous.get().revision()) {
      String kind =
          entry.revision() == previous.get().revision() ? "DUPLICATE_DELIVERY" : "STALE_REVISION";
      metrics.item(connector.kind(), kind);
      metrics.duplicateSuppressed(kind);
      return Ingested.NOTHING;
    }
    SourceItem.Status status =
        entry.deleted()
            ? SourceItem.Status.DELETED
            : cancelled(connector.kind(), entry.item())
                ? SourceItem.Status.CANCELLED
                : SourceItem.Status.ACTIVE;
    SourceItem item =
        new SourceItem(
            tenant,
            connector.connectorId(),
            entry.sourceId(),
            connector.kind(),
            entry.revision(),
            status,
            entry.item() == null ? "{}" : JSON.writeValueAsString(entry.item()),
            previous.map(SourceItem::candidateId).orElse(null),
            previous.map(SourceItem::firstSeenAt).orElse(now),
            now);
    items.upsert(item);
    items.recordRevision(item, runId);
    metrics.item(
        connector.kind(), previous.isEmpty() ? "NEW" : entry.deleted() ? "DELETED" : "UPDATED");
    int touched =
        switch (connector.kind()) {
          case CALENDAR -> calendar(connector, item, (CalendarEvent) entry.item(), now);
          case CRM -> crm(connector, item, (CrmRecord) entry.item(), now);
          case HRIS -> hris(connector, item, (HrisRecord) entry.item(), now);
          case EXPENSE -> expense(connector, item, (ExpenseRecord) entry.item(), now);
        };
    return new Ingested(true, touched);
  }

  private static boolean cancelled(ConnectorKind kind, @Nullable Object item) {
    return switch (kind) {
      case CALENDAR -> item instanceof CalendarEvent e && "CANCELLED".equalsIgnoreCase(e.status());
      case CRM -> item instanceof CrmRecord r && "CANCELLED".equalsIgnoreCase(r.status());
      default -> false;
    };
  }

  // ------------------------------------------------------------------ calendar

  private int calendar(Connector c, SourceItem item, @Nullable CalendarEvent e, Instant now) {
    if (item.status() != SourceItem.Status.ACTIVE || e == null) {
      String reason =
          item.status() == SourceItem.Status.DELETED ? "SOURCE_DELETED" : "SOURCE_CANCELLED";
      String what =
          e == null ? "event " + item.sourceId() : "event " + DetectionRulesText.quote(e.title());
      return sourceGone(
          c,
          item,
          reason,
          what
              + (item.status() == SourceItem.Status.DELETED
                  ? " was deleted"
                  : " was cancelled by its organizer"),
          now);
    }
    int touched = 0;
    List<Person> people = new ArrayList<>();
    if (e.organizerEmail() != null && !e.organizerEmail().isBlank()) {
      people.add(new Person(e.organizerEmail(), true, null));
    }
    for (CalendarEvent.Attendee a : e.attendees()) {
      if (people.stream().noneMatch(p -> p.email().equalsIgnoreCase(a.email()))) {
        people.add(new Person(a.email(), false, a));
      }
    }
    for (Person p : people) {
      Optional<Employee> traveler = employees.findByEmail(c.tenant(), p.email());
      if (traveler.isEmpty()) {
        metrics.item(ConnectorKind.CALENDAR, "UNRESOLVED_IDENTITY");
        log.info("calendar {}: attendee not in the directory; ignored", item.sourceId());
        continue;
      }
      DetectionRules.Assessment a =
          DetectionRules.assessCalendar(e, traveler.get(), p.organizer(), p.attendee(), now);
      String primaryKey =
          "CALENDAR|" + c.connectorId() + "|" + item.sourceId() + "|" + traveler.get().employeeId();
      SourceRef ref =
          new SourceRef(
              c.connectorId(),
              ConnectorKind.CALENDAR,
              item.sourceId(),
              item.revision(),
              SourceRef.Role.PRIMARY);
      touched += apply(c, item, traveler.get(), a, primaryKey, ref, now);
    }
    return touched;
  }

  private record Person(
      String email, boolean organizer, CalendarEvent.@Nullable Attendee attendee) {}

  // ------------------------------------------------------------------ CRM

  private int crm(Connector c, SourceItem item, @Nullable CrmRecord r, Instant now) {
    if (item.status() != SourceItem.Status.ACTIVE || r == null) {
      String reason =
          item.status() == SourceItem.Status.DELETED ? "SOURCE_DELETED" : "SOURCE_CANCELLED";
      return sourceGone(
          c,
          item,
          reason,
          "CRM visit "
              + item.sourceId()
              + (item.status() == SourceItem.Status.DELETED ? " was deleted" : " was cancelled"),
          now);
    }
    Optional<Employee> owner = employees.findByEmail(c.tenant(), r.ownerEmail());
    if (owner.isEmpty()) {
      metrics.item(ConnectorKind.CRM, "UNRESOLVED_IDENTITY");
      return 0;
    }
    DetectionRules.Assessment a = DetectionRules.assessCrm(r, owner.get(), now);
    SourceRef ref =
        new SourceRef(
            c.connectorId(),
            ConnectorKind.CRM,
            item.sourceId(),
            item.revision(),
            SourceRef.Role.CORRELATED);
    // An explicit link to the calendar event of the same visit wins over every heuristic.
    if (a.candidate() && r.calendarEventId() != null && !r.calendarEventId().isBlank()) {
      Optional<DemandCandidate> linked =
          candidateOfCalendarEvent(c.tenant(), r.calendarEventId(), owner.get().employeeId());
      if (linked.isPresent()) {
        return attach(
            linked.get(),
            ref,
            "CRM visit "
                + item.sourceId()
                + " is linked to calendar event "
                + r.calendarEventId()
                + ": the same visit, one candidate.",
            item,
            now);
      }
    }
    String primaryKey =
        "CRM|" + c.connectorId() + "|" + item.sourceId() + "|" + owner.get().employeeId();
    return apply(
        c,
        item,
        owner.get(),
        a,
        primaryKey,
        new SourceRef(
            ref.connectorId(), ref.kind(), ref.sourceId(), ref.revision(), SourceRef.Role.PRIMARY),
        now);
  }

  private Optional<DemandCandidate> candidateOfCalendarEvent(
      TenantId tenant, String calendarEventId, String travelerId) {
    return items.byKind(tenant, ConnectorKind.CALENDAR).stream()
        .filter(i -> i.sourceId().equals(calendarEventId))
        .flatMap(
            i ->
                demand.list(tenant, travelerId, null, 200).stream()
                    .filter(
                        cand ->
                            cand.sources().stream()
                                .anyMatch(
                                    s ->
                                        s.connectorId().equals(i.connectorId())
                                            && s.sourceId().equals(i.sourceId()))))
        .filter(cand -> cand.status().open())
        .findFirst()
        .flatMap(cand -> demand.lock(tenant, cand.candidateId()));
  }

  // ------------------------------------------------------------------ HRIS

  private int hris(Connector c, SourceItem item, @Nullable HrisRecord h, Instant now) {
    if (h == null) {
      return 0;
    }
    Employee e =
        new Employee(
            c.tenant(),
            h.employeeId(),
            h.email(),
            h.displayName(),
            h.workLocation(),
            DetectionRules.zone(h.timeZone(), ZoneId.of("UTC")),
            h.managerEmployeeId() == null || h.managerEmployeeId().isBlank()
                ? null
                : h.managerEmployeeId(),
            h.active() && item.status() == SourceItem.Status.ACTIVE,
            item.revision(),
            now);
    employees.upsert(e);
    if (e.active()) {
      return 0;
    }
    int touched = 0;
    for (DemandCandidate cand : demand.openForTraveler(c.tenant(), e.employeeId())) {
      withdraw(
          cand,
          "TRAVELER_INACTIVE",
          e.displayName() + " (" + e.employeeId() + ") is no longer an active employee per HRIS.",
          now);
      touched++;
    }
    return touched;
  }

  // ------------------------------------------------------------------ expense

  private int expense(Connector c, SourceItem item, @Nullable ExpenseRecord x, Instant now) {
    if (x == null || item.status() != SourceItem.Status.ACTIVE) {
      return 0;
    }
    Optional<Employee> who = employees.findByEmail(c.tenant(), x.employeeEmail());
    if (who.isEmpty() || x.city() == null) {
      return 0;
    }
    int touched = 0;
    for (DemandCandidate cand : demand.openFor(c.tenant(), who.get().employeeId(), x.city())) {
      SourceRef ref = expenseRef(c.connectorId(), item, x, cand);
      if (ref == null) {
        continue;
      }
      touched += attach(cand, ref, expenseExplanation(ref, x), item, now);
    }
    return touched;
  }

  /**
   * ENRICHMENT for past spend in the city; DUPLICATE_SIGNAL for planned or reported travel
   * overlapping the dates.
   */
  private static @Nullable SourceRef expenseRef(
      String connectorId, SourceItem item, ExpenseRecord x, DemandCandidate cand) {
    if (cand.startDate() == null || cand.endDate() == null) {
      return null;
    }
    boolean overlaps =
        !x.endDate().isBefore(cand.startDate()) && !x.startDate().isAfter(cand.endDate());
    if (overlaps
        && ("PREAPPROVAL".equalsIgnoreCase(x.kind()) || "TRIP_REPORT".equalsIgnoreCase(x.kind()))) {
      return new SourceRef(
          connectorId,
          ConnectorKind.EXPENSE,
          item.sourceId(),
          item.revision(),
          SourceRef.Role.DUPLICATE_SIGNAL);
    }
    if (x.endDate().isBefore(cand.startDate())) {
      return new SourceRef(
          connectorId,
          ConnectorKind.EXPENSE,
          item.sourceId(),
          item.revision(),
          SourceRef.Role.ENRICHMENT);
    }
    return null;
  }

  private static String expenseExplanation(SourceRef ref, ExpenseRecord x) {
    return ref.role() == SourceRef.Role.DUPLICATE_SIGNAL
        ? "Expense "
            + x.kind().toLowerCase(java.util.Locale.ROOT)
            + " "
            + x.sourceId()
            + " already covers "
            + x.city()
            + " on "
            + DetectionRulesText.dates(x.startDate(), x.endDate())
            + ": this trip may already exist; a person must check before booking."
        : "Expense history: "
            + x.kind().toLowerCase(java.util.Locale.ROOT)
            + " "
            + x.sourceId()
            + " in "
            + x.city()
            + " on "
            + DetectionRulesText.dates(x.startDate(), x.endDate())
            + " (a previous visit, for context only).";
  }

  /** When a candidate is born, spend already on file is applied to it the same way. */
  private void enrichFromExpenses(DemandCandidate cand, Employee traveler, Instant now) {
    if (cand.destination() == null) {
      return;
    }
    for (SourceItem it : items.byKind(cand.tenant(), ConnectorKind.EXPENSE)) {
      if (it.status() != SourceItem.Status.ACTIVE) {
        continue;
      }
      ExpenseRecord x = JSON.readValue(it.normalizedJson(), ExpenseRecord.class);
      if (!x.employeeEmail().equalsIgnoreCase(traveler.email())
          || !cand.destination().equals(x.city())) {
        continue;
      }
      SourceRef ref = expenseRef(it.connectorId(), it, x, cand);
      if (ref != null) {
        DemandCandidate fresh = demand.find(cand.tenant(), cand.candidateId()).orElse(cand);
        attach(fresh, ref, expenseExplanation(ref, x), it, now);
      }
    }
  }

  // ------------------------------------------------------------------ apply an assessment

  private int apply(
      Connector c,
      SourceItem item,
      Employee traveler,
      DetectionRules.Assessment a,
      String primaryKey,
      SourceRef ref,
      Instant now) {
    Optional<DemandCandidate> own = demand.findByPrimaryKey(c.tenant(), primaryKey);
    if (!a.candidate()) {
      metrics.candidate(a.decision().name());
      if (own.isPresent()) {
        DemandCandidate cand = own.get();
        if (cand.status().open()) {
          String reason =
              switch (a.decision()) {
                case IGNORE_DECLINED -> "ATTENDANCE_DECLINED";
                case IGNORE_CANCELLED -> "SOURCE_CANCELLED";
                case IGNORE_INACTIVE_TRAVELER -> "TRAVELER_INACTIVE";
                default -> "NO_LONGER_TRAVEL";
              };
          withdraw(withRef(cand, ref), reason, cap(a.explanation()), now);
          return 1;
        }
        if (cand.status() == DemandStatus.CONVERTED) {
          changedAfterConversion(withRef(cand, ref), "CANCELLED", cap(a.explanation()), now);
          return 1;
        }
      }
      log.info("{} {}: {}", c.kind(), item.sourceId(), a.explanation());
      return 0;
    }
    DetectionRules.Draft d = Objects.requireNonNull(a.draft());
    if (own.isEmpty()) {
      // Another source may already describe this visit for this traveler (a CRM visit before its
      // calendar event, or the other way round): documented rule, one candidate.
      Optional<DemandCandidate> related = overlapping(c.tenant(), d);
      if (related.isPresent()
          && related.get().sources().stream().noneMatch(s -> s.key().equals(ref.key()))) {
        SourceRef correlated =
            new SourceRef(
                ref.connectorId(),
                ref.kind(),
                ref.sourceId(),
                ref.revision(),
                SourceRef.Role.CORRELATED);
        metrics.duplicateSuppressed("CORRELATED_SOURCE");
        return attach(
            related.get(),
            correlated,
            describe(ref.kind(), item.sourceId())
                + " describes the same visit as "
                + related.get().candidateId()
                + " (same traveler, city and dates): one candidate.",
            item,
            now);
      }
      if (related.isPresent()) {
        return touch(related.get(), ref, item, now);
      }
      DemandCandidate created = create(c.tenant(), d, ref, a.explanation(), primaryKey, now);
      items.link(c.tenant(), c.connectorId(), item.sourceId(), created.candidateId());
      metrics.candidate(created.status().name());
      enrichFromExpenses(created, traveler, now);
      flagAdjacent(demand.find(c.tenant(), created.candidateId()).orElse(created), now);
      return 1;
    }
    return update(own.get(), d, ref, a.explanation(), item, now);
  }

  private String describe(ConnectorKind kind, String sourceId) {
    return (kind == ConnectorKind.CALENDAR
            ? "Calendar event "
            : kind == ConnectorKind.CRM ? "CRM visit " : kind.name() + " item ")
        + sourceId;
  }

  /** An open candidate of the same traveler in the same city whose dates overlap the draft's. */
  private Optional<DemandCandidate> overlapping(TenantId tenant, DetectionRules.Draft d) {
    if (d.destination() == null || d.startDate() == null || d.endDate() == null) {
      return Optional.empty();
    }
    return demand.openFor(tenant, d.travelerId(), d.destination()).stream()
        .filter(cand -> cand.startDate() != null && cand.endDate() != null)
        .filter(
            cand ->
                !cand.endDate().isBefore(d.startDate()) && !cand.startDate().isAfter(d.endDate()))
        .findFirst();
  }

  /** Same traveler and city on adjacent days: not merged, but both are flagged for a person. */
  private void flagAdjacent(DemandCandidate created, Instant now) {
    if (created.destination() == null || created.startDate() == null || created.endDate() == null) {
      return;
    }
    for (DemandCandidate other :
        demand.openFor(created.tenant(), created.travelerId(), created.destination())) {
      if (other.candidateId().equals(created.candidateId())
          || other.startDate() == null
          || other.endDate() == null) {
        continue;
      }
      boolean adjacent =
          other.startDate().equals(created.endDate().plusDays(1))
              || other.endDate().equals(created.startDate().minusDays(1));
      if (adjacent) {
        review(
            created,
            "POSSIBLY_RELATED:" + other.candidateId(),
            "Another candidate ("
                + other.candidateId()
                + ") has this traveler in "
                + created.destination()
                + " on adjacent days; a person decides whether it is one trip.",
            now);
        DemandCandidate fresh = demand.find(other.tenant(), other.candidateId()).orElse(other);
        review(
            fresh,
            "POSSIBLY_RELATED:" + created.candidateId(),
            "Another candidate ("
                + created.candidateId()
                + ") has this traveler in "
                + created.destination()
                + " on adjacent days; a person decides whether it is one trip.",
            now);
      }
    }
  }

  private DemandCandidate create(
      TenantId tenant,
      DetectionRules.Draft d,
      SourceRef ref,
      String explanation,
      String primaryKey,
      Instant now) {
    List<String> reasons = new ArrayList<>();
    DemandStatus status =
        d.missing().isEmpty() ? DemandStatus.ACTIONABLE : DemandStatus.NEEDS_REVIEW;
    DemandCandidate cand =
        new DemandCandidate(
            Ids.newId(IdPrefix.DEMAND),
            tenant,
            d.travelerId(),
            status,
            d.origin(),
            d.destination(),
            d.startDate(),
            d.endDate(),
            d.timeZone(),
            d.windowStart(),
            d.windowEnd(),
            d.purpose(),
            d.missing(),
            reasons,
            List.of(ref),
            DetectionRules.VERSION,
            cap(explanation),
            null,
            null,
            primaryKey,
            0,
            now,
            now);
    demand.insert(cand);
    demand.transition(
        cand.candidateId(), tenant, null, status, "DETECTED", cap(explanation), DETECTOR.id(), now);
    outbox.append(DemandEvents.detected(cand, clock));
    return cand;
  }

  /**
   * A redelivery or a change of the primary source: update the same candidate, never a second one.
   */
  private int update(
      DemandCandidate cand,
      DetectionRules.Draft d,
      SourceRef ref,
      String explanation,
      SourceItem item,
      Instant now) {
    boolean rescheduled =
        !Objects.equals(cand.startDate(), d.startDate())
            || !Objects.equals(cand.endDate(), d.endDate());
    boolean moved = !Objects.equals(cand.destination(), d.destination());
    boolean same =
        !rescheduled
            && !moved
            && Objects.equals(cand.purpose(), d.purpose())
            && cand.missing().equals(d.missing());
    if (cand.status() == DemandStatus.CONVERTED) {
      if (rescheduled || moved) {
        changedAfterConversion(
            withRef(cand, ref),
            moved ? "LOCATION_CHANGED" : "RESCHEDULED",
            describe(ref.kind(), item.sourceId())
                + (moved
                    ? " moved to " + d.destination()
                    : " moved to " + DetectionRulesText.dates(d.startDate(), d.endDate()))
                + " after the trip was requested: the trip was not changed automatically; review it.",
            now);
        return 1;
      }
      return touch(cand, ref, item, now);
    }
    if (cand.status() == DemandStatus.DISMISSED) {
      return touch(cand, ref, item, now);
    }
    if (same && cand.status() != DemandStatus.WITHDRAWN) {
      return touch(cand, ref, item, now);
    }
    List<String> reasons = new ArrayList<>(cand.reviewReasons());
    reasons.removeIf(r -> r.startsWith("SOURCE_CANCELLED:") || r.startsWith("SOURCE_DELETED:"));
    DemandStatus previous = cand.status();
    DemandStatus next =
        d.missing().isEmpty() && reasons.isEmpty()
            ? DemandStatus.ACTIONABLE
            : DemandStatus.NEEDS_REVIEW;
    String reason =
        previous == DemandStatus.WITHDRAWN
            ? "RESTORED"
            : rescheduled ? "RESCHEDULED" : "SOURCE_CHANGED";
    DemandCandidate updated =
        new DemandCandidate(
            cand.candidateId(),
            cand.tenant(),
            cand.travelerId(),
            next,
            d.origin(),
            d.destination(),
            d.startDate(),
            d.endDate(),
            d.timeZone(),
            d.windowStart(),
            d.windowEnd(),
            d.purpose(),
            d.missing(),
            reasons,
            replaceRef(cand.sources(), ref),
            DetectionRules.VERSION,
            cap(explanation),
            cand.tripId(),
            cand.conversionKey(),
            cand.primaryKey(),
            cand.version(),
            cand.createdAt(),
            now);
    save(updated, cand.version());
    demand.transition(
        cand.candidateId(),
        cand.tenant(),
        previous,
        next,
        reason,
        cap(explanation),
        DETECTOR.id(),
        now);
    outbox.append(DemandEvents.updated(updated, previous, reason, clock));
    return 1;
  }

  /** Same content, newer revision: the evidence is updated, nothing else moves. */
  private int touch(DemandCandidate cand, SourceRef ref, SourceItem item, Instant now) {
    if (cand.sources().stream()
        .anyMatch(s -> s.key().equals(ref.key()) && s.revision() == ref.revision())) {
      return 0;
    }
    DemandCandidate updated = withRef(cand, ref);
    save(updated, cand.version());
    items.link(cand.tenant(), item.connectorId(), item.sourceId(), cand.candidateId());
    return 1;
  }

  /** A second source joins a candidate (correlation, enrichment, a duplicate signal). */
  private int attach(
      DemandCandidate cand, SourceRef ref, String explanation, SourceItem item, Instant now) {
    if (cand.sources().stream()
        .anyMatch(
            s ->
                s.key().equals(ref.key())
                    && s.revision() == ref.revision()
                    && s.role() == ref.role())) {
      return 0;
    }
    List<String> reasons = new ArrayList<>(cand.reviewReasons());
    if (ref.role() == SourceRef.Role.DUPLICATE_SIGNAL) {
      String flag = "POSSIBLE_DUPLICATE:" + ref.sourceId();
      if (!reasons.contains(flag)) {
        reasons.add(flag);
      }
    }
    DemandStatus previous = cand.status();
    DemandStatus next =
        previous.open()
            ? (cand.missing().isEmpty() && reasons.isEmpty()
                ? DemandStatus.ACTIONABLE
                : DemandStatus.NEEDS_REVIEW)
            : previous;
    DemandCandidate updated =
        new DemandCandidate(
            cand.candidateId(),
            cand.tenant(),
            cand.travelerId(),
            next,
            cand.origin(),
            cand.destination(),
            cand.startDate(),
            cand.endDate(),
            cand.timeZone(),
            cand.windowStart(),
            cand.windowEnd(),
            cand.purpose(),
            cand.missing(),
            reasons,
            replaceRef(cand.sources(), ref),
            cand.rulesVersion(),
            cap(cand.explanation() + " " + explanation),
            cand.tripId(),
            cand.conversionKey(),
            cand.primaryKey(),
            cand.version(),
            cand.createdAt(),
            now);
    save(updated, cand.version());
    items.link(cand.tenant(), item.connectorId(), item.sourceId(), cand.candidateId());
    String reason =
        ref.role() == SourceRef.Role.DUPLICATE_SIGNAL ? "REVIEW_REQUIRED" : "SOURCE_ADDED";
    demand.transition(
        cand.candidateId(),
        cand.tenant(),
        previous,
        next,
        reason,
        cap(explanation),
        DETECTOR.id(),
        now);
    if (previous.open()) {
      outbox.append(DemandEvents.updated(updated, previous, reason, clock));
    }
    return 1;
  }

  private void review(DemandCandidate cand, String flag, String explanation, Instant now) {
    if (!cand.status().open() || cand.reviewReasons().contains(flag)) {
      return;
    }
    List<String> reasons = new ArrayList<>(cand.reviewReasons());
    reasons.add(flag);
    DemandStatus previous = cand.status();
    DemandCandidate updated =
        new DemandCandidate(
            cand.candidateId(),
            cand.tenant(),
            cand.travelerId(),
            DemandStatus.NEEDS_REVIEW,
            cand.origin(),
            cand.destination(),
            cand.startDate(),
            cand.endDate(),
            cand.timeZone(),
            cand.windowStart(),
            cand.windowEnd(),
            cand.purpose(),
            cand.missing(),
            reasons,
            cand.sources(),
            cand.rulesVersion(),
            cap(cand.explanation() + " " + explanation),
            cand.tripId(),
            cand.conversionKey(),
            cand.primaryKey(),
            cand.version(),
            cand.createdAt(),
            now);
    save(updated, cand.version());
    demand.transition(
        cand.candidateId(),
        cand.tenant(),
        previous,
        DemandStatus.NEEDS_REVIEW,
        "REVIEW_REQUIRED",
        cap(explanation),
        DETECTOR.id(),
        now);
    outbox.append(DemandEvents.updated(updated, previous, "REVIEW_REQUIRED", clock));
  }

  /** The primary source is gone: open candidates are withdrawn; converted ones are flagged. */
  private int sourceGone(
      Connector c, SourceItem item, String reason, String explanation, Instant now) {
    int touched = 0;
    for (DemandCandidate cand : candidatesOf(c.tenant(), item)) {
      boolean primary =
          cand.sources().stream()
              .anyMatch(
                  s ->
                      s.key().equals(c.connectorId() + "|" + item.sourceId())
                          && s.role() == SourceRef.Role.PRIMARY);
      SourceRef ref =
          cand.sources().stream()
              .filter(s -> s.key().equals(c.connectorId() + "|" + item.sourceId()))
              .findFirst()
              .map(
                  s ->
                      new SourceRef(
                          s.connectorId(), s.kind(), s.sourceId(), item.revision(), s.role()))
              .orElse(null);
      DemandCandidate marked = ref == null ? cand : withRef(cand, ref);
      if (cand.status() == DemandStatus.CONVERTED) {
        changedAfterConversion(
            marked,
            "CANCELLED",
            explanation
                + " after the trip was requested: the trip was not cancelled automatically; review it.",
            now);
        touched++;
      } else if (cand.status().open() && primary) {
        withdraw(marked, reason, explanation + ".", now);
        touched++;
      } else if (cand.status().open()) {
        review(
            marked,
            reason + ":" + item.sourceId(),
            explanation + "; the candidate still rests on its other sources.",
            now);
        touched++;
      }
    }
    return touched;
  }

  private List<DemandCandidate> candidatesOf(TenantId tenant, SourceItem item) {
    List<DemandCandidate> out = new ArrayList<>();
    if (item.candidateId() != null) {
      demand.lock(tenant, item.candidateId()).ifPresent(out::add);
    }
    for (DemandCandidate cand : demand.list(tenant, null, null, 500)) {
      if (out.stream().noneMatch(o -> o.candidateId().equals(cand.candidateId()))
          && cand.sources().stream()
              .anyMatch(
                  s ->
                      s.connectorId().equals(item.connectorId())
                          && s.sourceId().equals(item.sourceId()))) {
        demand.lock(tenant, cand.candidateId()).ifPresent(out::add);
      }
    }
    return out;
  }

  private void withdraw(DemandCandidate cand, String reason, String explanation, Instant now) {
    DemandStatus previous = cand.status();
    DemandCandidate updated =
        withStatus(cand, DemandStatus.WITHDRAWN, cap(cand.explanation() + " " + explanation), now);
    save(updated, cand.version());
    demand.transition(
        cand.candidateId(),
        cand.tenant(),
        previous,
        DemandStatus.WITHDRAWN,
        reason,
        cap(explanation),
        DETECTOR.id(),
        now);
    outbox.append(DemandEvents.withdrawn(updated, previous, reason, cap(explanation), clock));
    metrics.candidate("WITHDRAWN");
  }

  private void changedAfterConversion(
      DemandCandidate cand, String change, String explanation, Instant now) {
    String flag = "CHANGED_AFTER_CONVERSION:" + change;
    List<String> reasons = new ArrayList<>(cand.reviewReasons());
    if (!reasons.contains(flag)) {
      reasons.add(flag);
    }
    DemandCandidate updated =
        new DemandCandidate(
            cand.candidateId(),
            cand.tenant(),
            cand.travelerId(),
            cand.status(),
            cand.origin(),
            cand.destination(),
            cand.startDate(),
            cand.endDate(),
            cand.timeZone(),
            cand.windowStart(),
            cand.windowEnd(),
            cand.purpose(),
            cand.missing(),
            reasons,
            cand.sources(),
            cand.rulesVersion(),
            cap(cand.explanation() + " " + explanation),
            cand.tripId(),
            cand.conversionKey(),
            cand.primaryKey(),
            cand.version(),
            cand.createdAt(),
            now);
    save(updated, cand.version());
    demand.transition(
        cand.candidateId(),
        cand.tenant(),
        cand.status(),
        cand.status(),
        "CHANGED_AFTER_CONVERSION",
        cap(explanation),
        DETECTOR.id(),
        now);
    outbox.append(DemandEvents.changedAfterConversion(updated, change, cap(explanation), clock));
    metrics.candidate("CHANGED_AFTER_CONVERSION");
  }

  // ================================================================== people

  @Transactional(readOnly = true)
  public DemandCandidate get(RequestPrincipal me, String candidateId) {
    DemandCandidate cand =
        demand
            .find(me.tenant(), candidateId)
            .orElseThrow(() -> new ApiException.NotFound("demand candidate", candidateId));
    if (!DemandAccess.canRead(me, cand, employees.find(me.tenant(), cand.travelerId()))) {
      throw new ApiException.NotFound("demand candidate", candidateId);
    }
    return cand;
  }

  @Transactional(readOnly = true)
  public List<DemandCandidate> list(
      RequestPrincipal me, @Nullable String travelerId, @Nullable DemandStatus status) {
    List<DemandCandidate> all = demand.list(me.tenant(), travelerId, status, 500);
    Map<String, Optional<Employee>> travelers = new LinkedHashMap<>();
    return all.stream()
        .filter(
            c ->
                DemandAccess.canRead(
                    me,
                    c,
                    travelers.computeIfAbsent(
                        c.travelerId(), id -> employees.find(me.tenant(), id))))
        .toList();
  }

  @Transactional(readOnly = true)
  public List<DemandTransition> history(RequestPrincipal me, String candidateId) {
    DemandCandidate cand = get(me, candidateId);
    return demand.transitions(me.tenant(), cand.candidateId());
  }

  public record Evidence(SourceItem item, List<SourceItemRepository.Revision> revisions) {}

  @Transactional(readOnly = true)
  public List<Evidence> evidence(RequestPrincipal me, String candidateId) {
    DemandCandidate cand = get(me, candidateId);
    List<Evidence> out = new ArrayList<>();
    for (SourceRef ref : cand.sources()) {
      items
          .find(me.tenant(), ref.connectorId(), ref.sourceId())
          .ifPresent(
              item ->
                  out.add(
                      new Evidence(
                          item, items.revisions(me.tenant(), ref.connectorId(), ref.sourceId()))));
    }
    return out;
  }

  /** A person supplies what the sources did not: the destination and/or the dates. */
  @Transactional
  public DemandCandidate resolveDetails(
      RequestPrincipal me,
      String candidateId,
      @Nullable String destination,
      @Nullable LocalDate startDate,
      @Nullable LocalDate endDate,
      String idempotencyKey) {
    DemandCandidate cand = lockForAction(me, candidateId);
    if (!cand.status().open()) {
      throw new ApiException.Conflict(
          "NOT_OPEN", "candidate " + candidateId + " is " + cand.status());
    }
    Employee traveler =
        employees
            .find(me.tenant(), cand.travelerId())
            .orElseThrow(
                () ->
                    new ApiException.Conflict(
                        "TRAVELER_UNKNOWN", "the traveler is not in the directory"));
    String dest = destination == null ? cand.destination() : destination;
    LocalDate start = startDate == null ? cand.startDate() : startDate;
    LocalDate end = endDate == null ? cand.endDate() : endDate;
    if (dest != null && io.travelos.common.geo.Locations.zoneOf(dest).isEmpty()) {
      throw new ApiException.Unprocessable(
          "DESTINATION_UNKNOWN",
          "the platform does not know " + dest + "'s clock; use an IATA code it serves");
    }
    if (dest != null && dest.equals(traveler.workLocation())) {
      throw new ApiException.Unprocessable(
          "DESTINATION_IS_WORK_LOCATION",
          dest + " is the traveler's own work location: no travel; dismiss the candidate instead");
    }
    if (start != null && end != null && end.isBefore(start)) {
      throw new ApiException.Unprocessable("DATES_INVALID", "endDate is before startDate");
    }
    List<String> missing = new ArrayList<>();
    if (dest == null) {
      missing.add("destination");
    }
    if (start == null || end == null) {
      missing.add("startDate");
    }
    Instant windowStart = cand.windowStart();
    Instant windowEnd = cand.windowEnd();
    ZoneId zone =
        dest == null
            ? DetectionRules.zone(cand.timeZone(), traveler.timeZone())
            : io.travelos.common.geo.Locations.zoneOrThrow(dest);
    if (start != null
        && end != null
        && (startDate != null || endDate != null || windowStart == null)) {
      windowStart = start.atTime(9, 0).atZone(zone).toInstant();
      windowEnd = end.atTime(17, 0).atZone(zone).toInstant();
    }
    List<String> reasons = new ArrayList<>(cand.reviewReasons());
    DemandStatus previous = cand.status();
    DemandStatus next =
        missing.isEmpty() && reasons.isEmpty()
            ? DemandStatus.ACTIONABLE
            : DemandStatus.NEEDS_REVIEW;
    String detail =
        "details supplied by "
            + me.principal().id()
            + ": "
            + (destination == null ? "" : "destination " + destination + " ")
            + (startDate == null && endDate == null ? "" : "dates " + start + " to " + end);
    DemandCandidate updated =
        new DemandCandidate(
            cand.candidateId(),
            cand.tenant(),
            cand.travelerId(),
            next,
            cand.origin(),
            dest,
            start,
            end,
            zone.getId(),
            windowStart,
            windowEnd,
            cand.purpose(),
            missing,
            reasons,
            cand.sources(),
            cand.rulesVersion(),
            cap(cand.explanation() + " " + detail + "."),
            cand.tripId(),
            cand.conversionKey(),
            cand.primaryKey(),
            cand.version(),
            cand.createdAt(),
            clock.instant());
    save(updated, cand.version());
    demand.transition(
        cand.candidateId(),
        cand.tenant(),
        previous,
        next,
        "DETAILS_RESOLVED",
        detail + " [" + idempotencyKey + "]",
        me.principal().id(),
        clock.instant());
    outbox.append(DemandEvents.updated(updated, previous, "DETAILS_RESOLVED", clock));
    return updated;
  }

  /**
   * A person clears a review flag they have looked into (a possible duplicate, a related
   * candidate).
   */
  @Transactional
  public DemandCandidate clearReview(RequestPrincipal me, String candidateId, String flag) {
    DemandCandidate cand = lockForAction(me, candidateId);
    if (!cand.status().open() || !cand.reviewReasons().contains(flag)) {
      return cand;
    }
    List<String> reasons = new ArrayList<>(cand.reviewReasons());
    reasons.remove(flag);
    DemandStatus previous = cand.status();
    DemandStatus next =
        cand.missing().isEmpty() && reasons.isEmpty()
            ? DemandStatus.ACTIONABLE
            : DemandStatus.NEEDS_REVIEW;
    DemandCandidate updated =
        new DemandCandidate(
            cand.candidateId(),
            cand.tenant(),
            cand.travelerId(),
            next,
            cand.origin(),
            cand.destination(),
            cand.startDate(),
            cand.endDate(),
            cand.timeZone(),
            cand.windowStart(),
            cand.windowEnd(),
            cand.purpose(),
            cand.missing(),
            reasons,
            cand.sources(),
            cand.rulesVersion(),
            cand.explanation(),
            cand.tripId(),
            cand.conversionKey(),
            cand.primaryKey(),
            cand.version(),
            cand.createdAt(),
            clock.instant());
    save(updated, cand.version());
    demand.transition(
        cand.candidateId(),
        cand.tenant(),
        previous,
        next,
        "DETAILS_RESOLVED",
        "review flag " + flag + " cleared",
        me.principal().id(),
        clock.instant());
    outbox.append(DemandEvents.updated(updated, previous, "DETAILS_RESOLVED", clock));
    return updated;
  }

  @Transactional
  public DemandCandidate dismiss(
      RequestPrincipal me, String candidateId, @Nullable String reason, String idempotencyKey) {
    DemandCandidate cand = lockForAction(me, candidateId);
    if (cand.status() == DemandStatus.DISMISSED) {
      return cand;
    }
    if (!cand.status().open()) {
      throw new ApiException.Conflict(
          "NOT_OPEN", "candidate " + candidateId + " is " + cand.status());
    }
    DemandStatus previous = cand.status();
    DemandCandidate updated =
        withStatus(cand, DemandStatus.DISMISSED, cand.explanation(), clock.instant());
    save(updated, cand.version());
    demand.transition(
        cand.candidateId(),
        cand.tenant(),
        previous,
        DemandStatus.DISMISSED,
        "DISMISSED",
        reason,
        me.principal().id(),
        clock.instant());
    outbox.append(DemandEvents.dismissed(updated, previous, me.principal(), reason, clock));
    metrics.candidate("DISMISSED");
    return updated;
  }

  /**
   * Actionable demand becomes a trip request through Travel Core's own creation, under the same
   * arranger rule and the same idempotency key every time: repeated or concurrent conversions of
   * one candidate yield one trip. The row lock serializes conversion against source updates.
   */
  @Transactional
  public DemandCandidate convert(RequestPrincipal me, String candidateId, String idempotencyKey) {
    DemandCandidate cand = lockForAction(me, candidateId);
    if (cand.status() == DemandStatus.CONVERTED) {
      metrics.conversion("ALREADY_CONVERTED");
      metrics.duplicateSuppressed("CONVERSION_REPEATED");
      return cand;
    }
    if (cand.status() != DemandStatus.ACTIONABLE) {
      metrics.conversion("REFUSED");
      throw new ApiException.Conflict(
          "NOT_ACTIONABLE",
          "candidate "
              + candidateId
              + " is "
              + cand.status()
              + (cand.missing().isEmpty() ? "" : "; missing " + cand.missing())
              + (cand.reviewReasons().isEmpty() ? "" : "; review " + cand.reviewReasons()));
    }
    Employee traveler =
        employees
            .find(me.tenant(), cand.travelerId())
            .filter(Employee::active)
            .orElseThrow(
                () ->
                    new ApiException.Conflict(
                        "TRAVELER_INACTIVE", "the traveler is not an active employee"));
    TravelIntent intent = TravelPlan.derive(cand, traveler.timeZone());
    String key = "demand:" + cand.candidateId() + ":CONVERT:1";
    Trip trip = trips.createTrip(me.tenant(), me, traveler, intent, key, cand.candidateId());
    DemandStatus previous = cand.status();
    DemandCandidate updated =
        new DemandCandidate(
            cand.candidateId(),
            cand.tenant(),
            cand.travelerId(),
            DemandStatus.CONVERTED,
            cand.origin(),
            cand.destination(),
            cand.startDate(),
            cand.endDate(),
            cand.timeZone(),
            cand.windowStart(),
            cand.windowEnd(),
            cand.purpose(),
            cand.missing(),
            cand.reviewReasons(),
            cand.sources(),
            cand.rulesVersion(),
            cap(
                cand.explanation()
                    + " Converted into trip "
                    + trip.getTripId()
                    + " by "
                    + me.principal().id()
                    + " ("
                    + TravelPlan.VERSION
                    + ")."),
            trip.getTripId(),
            idempotencyKey,
            cand.primaryKey(),
            cand.version(),
            cand.createdAt(),
            clock.instant());
    save(updated, cand.version());
    demand.transition(
        cand.candidateId(),
        cand.tenant(),
        previous,
        DemandStatus.CONVERTED,
        "CONVERTED",
        "trip " + trip.getTripId() + " [" + idempotencyKey + "]",
        me.principal().id(),
        clock.instant());
    outbox.append(DemandEvents.converted(updated, me.principal(), clock));
    metrics.conversion("CREATED");
    return updated;
  }

  // ------------------------------------------------------------------ helpers

  private DemandCandidate lockForAction(RequestPrincipal me, String candidateId) {
    DemandCandidate cand =
        demand
            .lock(me.tenant(), candidateId)
            .orElseThrow(() -> new ApiException.NotFound("demand candidate", candidateId));
    Optional<Employee> traveler = employees.find(me.tenant(), cand.travelerId());
    if (!DemandAccess.canRead(me, cand, traveler)) {
      throw new ApiException.NotFound("demand candidate", candidateId);
    }
    if (!DemandAccess.canAct(me, cand, traveler)) {
      throw new ApiException.Forbidden(
          "NOT_ALLOWED",
          "only the traveler, their manager (per HRIS) or a travel admin may act on this candidate");
    }
    return cand;
  }

  private void save(DemandCandidate updated, long expectedVersion) {
    if (!demand.update(updated, expectedVersion)) {
      throw new IllegalStateException(
          "candidate " + updated.candidateId() + " changed concurrently; retry");
    }
  }

  private static DemandCandidate withStatus(
      DemandCandidate c, DemandStatus status, String explanation, Instant now) {
    return new DemandCandidate(
        c.candidateId(),
        c.tenant(),
        c.travelerId(),
        status,
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
        c.sources(),
        c.rulesVersion(),
        explanation,
        c.tripId(),
        c.conversionKey(),
        c.primaryKey(),
        c.version(),
        c.createdAt(),
        now);
  }

  private static DemandCandidate withRef(DemandCandidate c, SourceRef ref) {
    return new DemandCandidate(
        c.candidateId(),
        c.tenant(),
        c.travelerId(),
        c.status(),
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
        replaceRef(c.sources(), ref),
        c.rulesVersion(),
        c.explanation(),
        c.tripId(),
        c.conversionKey(),
        c.primaryKey(),
        c.version(),
        c.createdAt(),
        c.updatedAt());
  }

  /** The reference list keeps every source; a newer revision of one replaces its entry in place. */
  private static List<SourceRef> replaceRef(List<SourceRef> refs, SourceRef ref) {
    List<SourceRef> out = new ArrayList<>();
    boolean found = false;
    for (SourceRef r : refs) {
      if (r.key().equals(ref.key())) {
        out.add(
            new SourceRef(
                r.connectorId(),
                r.kind(),
                r.sourceId(),
                Math.max(r.revision(), ref.revision()),
                r.role()));
        found = true;
      } else {
        out.add(r);
      }
    }
    if (!found) {
      out.add(ref);
    }
    return out;
  }

  static String cap(String s) {
    return s.length() <= 2000 ? s : s.substring(s.length() - 2000);
  }
}
