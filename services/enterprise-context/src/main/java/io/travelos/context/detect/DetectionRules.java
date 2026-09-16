package io.travelos.context.detect;

import io.travelos.common.geo.Locations;
import io.travelos.context.model.Employee;
import io.travelos.context.source.CalendarEvent;
import io.travelos.context.source.CrmRecord;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * The deterministic eligibility rules of travel-demand detection, version {@value #VERSION}. Every
 * decision is explained in words a traveler or an auditor can check against the source. No model is
 * involved: the rules read only structured fields; free text (titles, notes, locations as written)
 * is carried as data and quoted, never interpreted.
 */
public final class DetectionRules {
  public static final String VERSION = "rules-v1";

  /** Purpose text kept on a candidate: the source's own words, bounded. */
  static final int PURPOSE_MAX = 200;

  private DetectionRules() {}

  public enum Decision {
    CANDIDATE,
    IGNORE_VIRTUAL,
    IGNORE_DECLINED,
    IGNORE_NOT_ACCEPTED,
    IGNORE_CANCELLED,
    IGNORE_PAST,
    IGNORE_LOCAL,
    IGNORE_NOT_QUALIFYING,
    IGNORE_INACTIVE_TRAVELER
  }

  /** What a source record means for one verified traveler. */
  public record Assessment(Decision decision, @Nullable Draft draft, String explanation) {
    public boolean candidate() {
      return decision == Decision.CANDIDATE;
    }
  }

  /**
   * The demand a record describes, before correlation. {@code missing} names what a person must
   * supply before it can become a trip request.
   */
  public record Draft(
      String travelerId,
      String origin,
      @Nullable String destination,
      @Nullable LocalDate startDate,
      @Nullable LocalDate endDate,
      String timeZone,
      Instant windowStart,
      Instant windowEnd,
      @Nullable String purpose,
      List<String> missing) {
    public Draft {
      missing = List.copyOf(missing);
    }
  }

  // ------------------------------------------------------------------ calendar

  /**
   * A confirmed, accepted, in-person event at a known place away from the traveler's work location
   * qualifies. A missing or unresolved place makes a reviewable draft (a person supplies the
   * destination); nothing is ever invented.
   */
  public static Assessment assessCalendar(
      CalendarEvent e,
      Employee traveler,
      boolean organizer,
      CalendarEvent.@Nullable Attendee attendee,
      Instant now) {
    String who = traveler.displayName() + " (" + traveler.employeeId() + ")";
    if (!"CONFIRMED".equalsIgnoreCase(e.status())) {
      return ignore(Decision.IGNORE_CANCELLED, "event " + quote(e.title()) + " is cancelled");
    }
    if (!traveler.active()) {
      return ignore(Decision.IGNORE_INACTIVE_TRAVELER, who + " is not an active employee");
    }
    if (attendee != null && "DECLINED".equalsIgnoreCase(attendee.status())) {
      return ignore(Decision.IGNORE_DECLINED, who + " declined " + quote(e.title()));
    }
    if (!organizer && (attendee == null || !"ACCEPTED".equalsIgnoreCase(attendee.status()))) {
      return ignore(
          Decision.IGNORE_NOT_ACCEPTED,
          who + " has not accepted " + quote(e.title()) + " (" + attendeeStatus(attendee) + ")");
    }
    if (virtual(e)) {
      return ignore(Decision.IGNORE_VIRTUAL, quote(e.title()) + " is a virtual meeting");
    }
    if (!e.end().isAfter(now)) {
      return ignore(Decision.IGNORE_PAST, quote(e.title()) + " is in the past");
    }
    ZoneId zone = zone(e.timeZone(), traveler.timeZone());
    LocalDate startDate = e.start().atZone(zone).toLocalDate();
    LocalDate endDate = e.end().minusSeconds(1).atZone(zone).toLocalDate();
    if (endDate.isBefore(startDate)) {
      endDate = startDate;
    }
    String purpose = purpose(e.title());
    String city = e.location() == null ? null : blankToNull(e.location().city());
    List<String> missing = new ArrayList<>();
    String explanation;
    if (city == null) {
      missing.add("destination");
      explanation =
          "Accepted in-person event "
              + quote(e.title())
              + " on "
              + dates(startDate, endDate)
              + (e.location() == null || blankToNull(e.location().text()) == null
                  ? " has no location"
                  : " has a location the platform could not place: " + quote(e.location().text()))
              + "; a person must confirm where it is.";
    } else if (Locations.zoneOf(city).isEmpty()) {
      missing.add("destination");
      explanation =
          "Accepted in-person event "
              + quote(e.title())
              + " on "
              + dates(startDate, endDate)
              + " is at "
              + city
              + ", a place the platform cannot plan travel to yet; a person must confirm the"
              + " destination.";
    } else if (city.equals(traveler.workLocation())) {
      return ignore(
          Decision.IGNORE_LOCAL,
          quote(e.title()) + " is in " + city + ", " + who + "'s own work location: no travel");
    } else {
      explanation =
          "Accepted in-person event "
              + quote(e.title())
              + " in "
              + city
              + " on "
              + dates(startDate, endDate)
              + ", away from "
              + who
              + "'s work location "
              + traveler.workLocation()
              + ": travel required.";
    }
    return new Assessment(
        Decision.CANDIDATE,
        new Draft(
            traveler.employeeId(),
            traveler.workLocation(),
            city,
            startDate,
            endDate,
            zone.getId(),
            e.start(),
            e.end(),
            purpose,
            missing),
        explanation);
  }

  static boolean virtual(CalendarEvent e) {
    if ("VIRTUAL".equalsIgnoreCase(e.attendanceMode())) {
      return true;
    }
    if (e.location() != null && "VIRTUAL".equalsIgnoreCase(e.location().kind())) {
      return true;
    }
    boolean noPlace =
        e.location() == null
            || (blankToNull(e.location().text()) == null
                && blankToNull(e.location().city()) == null);
    return noPlace && blankToNull(e.conferencingUrl()) != null;
  }

  // ------------------------------------------------------------------ CRM

  /**
   * Only an explicitly scheduled on-site visit qualifies. A deal's value, its stage, or a note
   * never authorizes travel on its own.
   */
  public static Assessment assessCrm(CrmRecord r, Employee owner, Instant now) {
    String who = owner.displayName() + " (" + owner.employeeId() + ")";
    String account = r.accountName() == null ? "an account" : quote(r.accountName());
    if (!"VISIT".equalsIgnoreCase(r.kind())) {
      return ignore(
          Decision.IGNORE_NOT_QUALIFYING,
          "CRM "
              + r.kind().toLowerCase(Locale.ROOT)
              + " "
              + r.sourceId()
              + " for "
              + account
              + " is not a scheduled on-site visit; its value, stage or notes do not mean travel");
    }
    if (!r.onSite()) {
      return ignore(
          Decision.IGNORE_NOT_QUALIFYING,
          "CRM visit " + r.sourceId() + " for " + account + " is not on-site");
    }
    if ("CANCELLED".equalsIgnoreCase(r.status())) {
      return ignore(Decision.IGNORE_CANCELLED, "CRM visit " + r.sourceId() + " is cancelled");
    }
    if (!owner.active()) {
      return ignore(Decision.IGNORE_INACTIVE_TRAVELER, who + " is not an active employee");
    }
    if ("COMPLETED".equalsIgnoreCase(r.status())
        || (r.scheduledEnd() != null && !r.scheduledEnd().isAfter(now))
        || (r.scheduledEnd() == null
            && r.scheduledStart() != null
            && !r.scheduledStart().isAfter(now))) {
      return ignore(Decision.IGNORE_PAST, "CRM visit " + r.sourceId() + " already happened");
    }
    ZoneId zone = zone(r.timeZone(), owner.timeZone());
    List<String> missing = new ArrayList<>();
    LocalDate startDate = null;
    LocalDate endDate = null;
    Instant windowStart = r.scheduledStart();
    Instant windowEnd = r.scheduledEnd() == null ? r.scheduledStart() : r.scheduledEnd();
    if (windowStart == null) {
      missing.add("startDate");
      windowStart = now;
      windowEnd = now;
    } else {
      startDate = windowStart.atZone(zone).toLocalDate();
      endDate = windowEnd.minusSeconds(1).atZone(zone).toLocalDate();
      if (endDate.isBefore(startDate)) {
        endDate = startDate;
      }
    }
    String city = blankToNull(r.accountCity());
    String explanation;
    if (city == null || Locations.zoneOf(city).isEmpty()) {
      missing.add("destination");
      explanation =
          "Scheduled on-site visit to "
              + account
              + (startDate == null ? "" : " on " + dates(startDate, endDate))
              + (city == null
                  ? " has no location on the account"
                  : " is at " + city + ", a place the platform cannot plan travel to yet")
              + "; a person must confirm the destination.";
    } else if (city.equals(owner.workLocation())) {
      return ignore(
          Decision.IGNORE_LOCAL,
          "on-site visit to " + account + " is in " + city + ", " + who + "'s own work location");
    } else {
      explanation =
          "Scheduled on-site visit to "
              + account
              + " in "
              + city
              + (startDate == null ? " (date to be confirmed)" : " on " + dates(startDate, endDate))
              + ", away from "
              + who
              + "'s work location "
              + owner.workLocation()
              + ": travel required.";
    }
    return new Assessment(
        Decision.CANDIDATE,
        new Draft(
            owner.employeeId(),
            owner.workLocation(),
            city == null || Locations.zoneOf(city).isEmpty() ? null : city,
            startDate,
            endDate,
            zone.getId(),
            windowStart,
            windowEnd,
            purpose(r.accountName() == null ? "customer visit" : "visit: " + r.accountName()),
            missing),
        explanation);
  }

  // ------------------------------------------------------------------ helpers

  private static Assessment ignore(Decision d, String explanation) {
    return new Assessment(d, null, explanation);
  }

  public static ZoneId zone(@Nullable String id, ZoneId fallback) {
    if (id == null || id.isBlank()) {
      return fallback;
    }
    try {
      return ZoneId.of(id);
    } catch (RuntimeException e) {
      return fallback;
    }
  }

  static String dates(LocalDate start, LocalDate end) {
    return start.equals(end) ? start.toString() : start + " to " + end;
  }

  /** Free text is quoted and bounded: it is evidence, never a message to the platform. */
  static String quote(@Nullable String text) {
    if (text == null) {
      return "(untitled)";
    }
    String t = text.replace('\n', ' ').replace('\r', ' ').trim();
    if (t.length() > 80) {
      t = t.substring(0, 77) + "...";
    }
    return "'" + t + "'";
  }

  static @Nullable String purpose(@Nullable String text) {
    if (text == null || text.isBlank()) {
      return null;
    }
    String t = text.replace('\n', ' ').replace('\r', ' ').trim();
    return t.length() > PURPOSE_MAX ? t.substring(0, PURPOSE_MAX) : t;
  }

  private static String attendeeStatus(CalendarEvent.@Nullable Attendee a) {
    return a == null ? "not invited" : a.status().toLowerCase(Locale.ROOT).replace('_', ' ');
  }

  static @Nullable String blankToNull(@Nullable String s) {
    return s == null || s.isBlank() ? null : s;
  }
}
