package io.travelos.context.source;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A calendar event in our vocabulary. {@code title}, {@code description} and {@code location.text}
 * are the organizer's own words: data, shown to people, never an instruction.
 *
 * @param seriesId the recurring series this instance belongs to, when it is an instance
 * @param attendanceMode IN_PERSON | VIRTUAL | UNKNOWN (what the invitation says)
 * @param status CONFIRMED | CANCELLED
 */
public record CalendarEvent(
    String sourceId,
    @Nullable String seriesId,
    String title,
    @Nullable String description,
    String organizerEmail,
    List<Attendee> attendees,
    Instant start,
    Instant end,
    String timeZone,
    @Nullable Location location,
    @Nullable String conferencingUrl,
    String status,
    @Nullable String attendanceMode) {
  public CalendarEvent {
    attendees = attendees == null ? List.of() : List.copyOf(attendees);
  }

  /**
   * @param status ACCEPTED | DECLINED | TENTATIVE | NEEDS_ACTION
   */
  public record Attendee(String email, String status) {}

  /**
   * @param text the free-text location as written (untrusted)
   * @param city the resolved IATA city when the source could geocode it
   * @param kind PHYSICAL | VIRTUAL | UNKNOWN
   */
  public record Location(@Nullable String text, @Nullable String city, String kind) {}
}
