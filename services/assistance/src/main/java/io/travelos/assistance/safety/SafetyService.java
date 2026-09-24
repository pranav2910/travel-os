package io.travelos.assistance.safety;

import io.travelos.assistance.AssistanceProperties;
import io.travelos.assistance.events.AssistanceEvents;
import io.travelos.assistance.model.AssistanceCase;
import io.travelos.assistance.model.CaseKind;
import io.travelos.assistance.model.Priority;
import io.travelos.assistance.model.Queue;
import io.travelos.assistance.notify.NotificationRecords.Category;
import io.travelos.assistance.notify.NotificationService;
import io.travelos.assistance.safety.SafetyRecords.Advisory;
import io.travelos.assistance.safety.SafetyRecords.Affected;
import io.travelos.assistance.safety.SafetyRecords.Checkin;
import io.travelos.assistance.safety.SafetyRecords.CheckinStatus;
import io.travelos.assistance.safety.SafetyRecords.Severity;
import io.travelos.assistance.service.CaseAccess;
import io.travelos.assistance.service.CaseService;
import io.travelos.assistance.store.TripIndexRepository;
import io.travelos.common.geo.Locations;
import io.travelos.common.ids.IdPrefix;
import io.travelos.common.ids.Ids;
import io.travelos.spring.outbox.Outbox;
import io.travelos.spring.web.auth.RequestPrincipal;
import io.travelos.spring.web.error.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase 8: traveler safety. An advisory names places (countries and/or IATA cities) and a window;
 * the affected travelers are the booked trips that overlap it; each is told and, on a HIGH or
 * CRITICAL advisory, asked to check in. A traveler who asks for help, or who has not answered when
 * the grace period ends, becomes a SAFETY case for a person. Locations come from the platform's own
 * trip records, never from a device.
 */
@Service
public class SafetyService {
  private static final Logger log = LoggerFactory.getLogger(SafetyService.class);
  private static final String SYSTEM = "service/assistance";

  private final SafetyRepository store;
  private final TripIndexRepository trips;
  private final NotificationService notifications;
  private final CaseService cases;
  private final Outbox outbox;
  private final AssistanceProperties properties;
  private final Clock clock;

  public SafetyService(
      SafetyRepository store,
      TripIndexRepository trips,
      NotificationService notifications,
      CaseService cases,
      Outbox outbox,
      AssistanceProperties properties,
      Clock clock) {
    this.store = store;
    this.trips = trips;
    this.notifications = notifications;
    this.cases = cases;
    this.outbox = outbox;
    this.properties = properties;
    this.clock = clock;
  }

  public record Issue(
      String title,
      Severity severity,
      List<String> countries,
      List<String> cities,
      Instant startsAt,
      Instant endsAt,
      String text,
      @Nullable String source) {}

  @Transactional
  public Advisory issue(RequestPrincipal me, Issue issue) {
    if (!CaseAccess.worker(me)) {
      throw new ApiException.Forbidden(
          "NOT_A_TRAVEL_ADMIN", "TRAVEL_ADMIN or FINANCE role required");
    }
    if (issue.countries().isEmpty() && issue.cities().isEmpty()) {
      throw new ApiException.Unprocessable("PLACE_REQUIRED", "name at least one country or city");
    }
    if (!issue.endsAt().isAfter(issue.startsAt())) {
      throw new ApiException.Unprocessable("WINDOW_INVALID", "endsAt must be after startsAt");
    }
    Instant now = clock.instant();
    List<String> countries =
        issue.countries().stream().map(c -> c.trim().toUpperCase(java.util.Locale.ROOT)).toList();
    List<String> cities =
        issue.cities().stream().map(c -> c.trim().toUpperCase(java.util.Locale.ROOT)).toList();
    Advisory a =
        new Advisory(
            Ids.newId(IdPrefix.SAFETY_ADVISORY),
            me.tenant(),
            issue.title(),
            issue.severity(),
            countries,
            cities,
            issue.startsAt(),
            issue.endsAt(),
            issue.text(),
            issue.source(),
            true,
            me.principal().id(),
            now,
            issue.severity().needsCheckin() ? now.plus(properties.safety().checkinGrace()) : null);
    store.insert(a);
    int affected = 0;
    Set<String> told = new HashSet<>();
    for (TripIndexRepository.TripRef trip :
        trips.bookedOverlapping(me.tenant(), a.startsAt(), a.endsAt())) {
      if (trip.travelerId() == null || !touches(trip, a)) {
        continue;
      }
      store.insertAffected(
          new Affected(a.advisoryId(), me.tenant(), trip.travelerId(), trip.tripId(), now, null));
      affected++;
      if (told.add(trip.travelerId())) {
        notifications.notify(
            new NotificationService.Request(
                me.tenant(),
                trip.travelerId(),
                null,
                trip.travelerEmail(),
                Category.SAFETY,
                "travel.assistance.advisory-issued",
                "Safety advisory (" + a.severity() + "): " + a.title(),
                a.text()
                    + (a.severity().needsCheckin()
                        ? " Please check in on the advisory page to tell us you are safe."
                        : ""),
                "advisory",
                a.advisoryId(),
                trip.tripId(),
                "safety:" + a.advisoryId() + ":" + trip.travelerId(),
                a.severity().needsCheckin() ? "CRITICAL" : "HIGH",
                null));
      }
    }
    outbox.append(AssistanceEvents.advisoryIssued(a, affected, clock));
    log.info(
        "advisory {} ({}) issued by {}: {} affected traveler-trips",
        a.advisoryId(),
        a.severity(),
        me.principal().id(),
        affected);
    return a;
  }

  static boolean touches(TripIndexRepository.TripRef trip, Advisory a) {
    List<String> places = new ArrayList<>(trip.cities());
    if (trip.destination() != null) {
      places.add(trip.destination());
    }
    for (String place : places) {
      if (a.cities().contains(place)) {
        return true;
      }
      String country = Locations.place(place).map(Locations.Place::country).orElse(null);
      if (country != null && a.countries().contains(country)) {
        return true;
      }
    }
    return false;
  }

  @Transactional(readOnly = true)
  public List<Advisory> list(RequestPrincipal me, boolean activeOnly) {
    if (CaseAccess.worker(me)) {
      return store.list(me.tenant(), activeOnly);
    }
    return me.employeeId() == null ? List.of() : store.affecting(me.tenant(), me.employeeId());
  }

  public record Detail(Advisory advisory, List<Affected> affected, List<Checkin> checkins) {}

  @Transactional(readOnly = true)
  public Detail get(RequestPrincipal me, String advisoryId) {
    Advisory a =
        store
            .find(me.tenant(), advisoryId)
            .orElseThrow(() -> new ApiException.NotFound("advisory", advisoryId));
    if (CaseAccess.worker(me)) {
      return new Detail(
          a, store.affected(me.tenant(), advisoryId), store.checkins(me.tenant(), advisoryId));
    }
    List<Affected> mine =
        store.affected(me.tenant(), advisoryId).stream()
            .filter(f -> f.travelerId().equals(me.employeeId()))
            .toList();
    if (mine.isEmpty()) {
      throw new ApiException.NotFound("advisory", advisoryId);
    }
    return new Detail(
        a,
        mine,
        store.checkins(me.tenant(), advisoryId).stream()
            .filter(c -> c.travelerId().equals(me.employeeId()))
            .toList());
  }

  @Transactional
  public void close(RequestPrincipal me, String advisoryId) {
    if (!CaseAccess.worker(me)) {
      throw new ApiException.Forbidden(
          "NOT_A_TRAVEL_ADMIN", "TRAVEL_ADMIN or FINANCE role required");
    }
    if (!store.close(me.tenant(), advisoryId)) {
      throw new ApiException.NotFound("advisory", advisoryId);
    }
  }

  /** A traveler answers; NEEDS_HELP opens a CRITICAL safety case at once. */
  @Transactional
  public Checkin checkin(
      RequestPrincipal me, String advisoryId, CheckinStatus status, @Nullable String note) {
    Advisory a =
        store
            .find(me.tenant(), advisoryId)
            .orElseThrow(() -> new ApiException.NotFound("advisory", advisoryId));
    String traveler = me.employeeIdOrThrow();
    List<Affected> mine =
        store.affected(me.tenant(), advisoryId).stream()
            .filter(f -> f.travelerId().equals(traveler))
            .toList();
    if (mine.isEmpty() && !CaseAccess.worker(me)) {
      throw new ApiException.NotFound("advisory", advisoryId);
    }
    Instant now = clock.instant();
    Checkin c =
        new Checkin(
            Ids.newId(IdPrefix.SAFETY_CHECKIN),
            advisoryId,
            me.tenant(),
            traveler,
            status,
            note,
            now);
    store.upsertCheckin(c);
    String tripId = mine.isEmpty() ? null : mine.getFirst().tripId();
    outbox.append(AssistanceEvents.checkinRecorded(c, tripId, clock));
    if (status == CheckinStatus.NEEDS_HELP) {
      AssistanceCase opened =
          cases.open(
              new CaseService.OpenCase(
                  me.tenant(),
                  CaseKind.SAFETY,
                  Priority.CRITICAL,
                  Queue.SAFETY,
                  "Traveler " + traveler + " asked for help: " + a.title(),
                  note,
                  tripId,
                  null,
                  traveler,
                  null,
                  null,
                  null,
                  "safety:help:" + advisoryId + ":" + traveler,
                  "Reach the traveler now; arrange what they need; record the outcome",
                  "TRAVEL_ADMIN",
                  null,
                  "travel.assistance.checkin-recorded",
                  me.principal().id()));
      store.linkCase(advisoryId, traveler, opened.caseId());
    } else {
      cases.settle(
          me.tenant(),
          "safety:help:" + advisoryId + ":" + traveler,
          "traveler checked in as safe",
          me.principal().id(),
          null);
      cases.settle(
          me.tenant(),
          "safety:silent:" + advisoryId + ":" + traveler,
          "traveler checked in as safe",
          me.principal().id(),
          null);
    }
    return c;
  }

  /**
   * The sweep: when the grace period ends, every affected traveler without a check-in is a case.
   */
  @Transactional
  public int openCasesForSilentTravelers() {
    Instant now = clock.instant();
    int opened = 0;
    for (Advisory a : store.checkinsDue(now)) {
      Set<String> answered = new HashSet<>();
      store.checkins(a.tenant(), a.advisoryId()).forEach(c -> answered.add(c.travelerId()));
      Set<String> done = new HashSet<>();
      for (Affected f : store.affected(a.tenant(), a.advisoryId())) {
        if (answered.contains(f.travelerId()) || !done.add(f.travelerId())) {
          continue;
        }
        AssistanceCase c =
            cases.open(
                new CaseService.OpenCase(
                    a.tenant(),
                    CaseKind.SAFETY,
                    Priority.CRITICAL,
                    Queue.SAFETY,
                    "No check-in from " + f.travelerId() + ": " + a.title(),
                    "affected by advisory "
                        + a.advisoryId()
                        + "; no answer within "
                        + properties.safety().checkinGrace(),
                    f.tripId(),
                    null,
                    f.travelerId(),
                    null,
                    null,
                    null,
                    "safety:silent:" + a.advisoryId() + ":" + f.travelerId(),
                    "Reach the traveler by every channel on file and confirm they are safe; record the outcome",
                    "TRAVEL_ADMIN",
                    null,
                    "travel.assistance.advisory-issued",
                    SYSTEM));
        store.linkCase(a.advisoryId(), f.travelerId(), c.caseId());
        opened++;
      }
      store.markCheckinsSwept(a.advisoryId());
    }
    return opened;
  }

  static Map<String, Object> unused() {
    return Map.of();
  }
}
