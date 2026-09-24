package io.travelos.context.source.live;

import io.travelos.context.IntegrationProperties;
import io.travelos.context.model.Connector;
import io.travelos.context.model.ConnectorKind;
import io.travelos.context.source.CalendarEvent;
import io.travelos.context.source.CalendarSource;
import io.travelos.context.source.SourceException;
import io.travelos.context.source.SourcePage;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

/**
 * Google Workspace calendars through the Calendar API v3, read as each covered user with a service
 * account's domain-wide delegation. Incremental: every user's {@code nextSyncToken} is kept in the
 * connector's watermark; a 410 (token expired) restarts that user from a full window. Cursor: user
 * index and page token.
 */
public final class GoogleCalendarSource extends LiveSource<CalendarEvent>
    implements CalendarSource {
  static final String SCOPE = "https://www.googleapis.com/auth/calendar.readonly";
  private final RestClient http;
  private final OAuth2Tokens tokens;
  private final IntegrationProperties.Google props;
  private final Clock clock;

  public GoogleCalendarSource(
      RestClient.Builder builder, IntegrationProperties.Google props, Clock clock) {
    this.http = builder.clone().build();
    this.tokens = new OAuth2Tokens(builder.clone().build(), clock);
    this.props = props;
    this.clock = clock;
  }

  @Override
  public ConnectorKind kind() {
    return ConnectorKind.CALENDAR;
  }

  @Override
  public String provider() {
    return "google-workspace";
  }

  @Override
  public SourcePage<CalendarEvent> fetch(Connector connector, String since, String cursor) {
    List<String> users = users(connector);
    Map<String, String> syncTokens = tokens(since);
    Position at = Position.parse(cursor);
    if (users.isEmpty() || at.user() >= users.size()) {
      return new SourcePage<>(List.of(), "", true, tokensJson(syncTokens));
    }
    String user = users.get(at.user());
    String token;
    try {
      token =
          tokens.jwtBearer(
              "google:" + user,
              props.tokenUrl(),
              props.serviceAccountEmail(),
              props.privateKey(),
              SCOPE,
              user);
    } catch (RuntimeException e) {
      throw IntegrationHttp.failure(provider(), e);
    }
    StringBuilder url =
        new StringBuilder(props.baseUrl())
            .append(
                "/calendar/v3/calendars/primary/events?singleEvents=true&showDeleted=true&maxResults=250");
    String syncToken = syncTokens.get(user);
    if (syncToken != null && !syncToken.isBlank()) {
      url.append("&syncToken=").append(syncToken);
    } else {
      Instant now = clock.instant();
      url.append("&timeMin=")
          .append(now.minus(Duration.ofDays(7)))
          .append("&timeMax=")
          .append(now.plus(Duration.ofDays(180)));
    }
    if (!at.page().isBlank()) {
      url.append("&pageToken=").append(at.page());
    }
    JsonNode body;
    try {
      body =
          IntegrationHttp.parse(
              http.get()
                  .uri(java.net.URI.create(url.toString()))
                  .header("Authorization", "Bearer " + token)
                  .retrieve()
                  .body(String.class));
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 410) {
        // the sync token expired: this user starts over from a full window on the next page
        syncTokens.remove(user);
        return new SourcePage<>(
            List.of(), new Position(at.user(), "").encode(), false, tokensJson(syncTokens));
      }
      throw IntegrationHttp.failure(provider(), e);
    } catch (RuntimeException e) {
      throw IntegrationHttp.failure(provider(), e);
    }
    List<SourcePage.Entry<CalendarEvent>> entries = new ArrayList<>();
    for (JsonNode item : body.path("items")) {
      String id = item.path("id").asString("");
      if (id.isBlank()) {
        continue;
      }
      boolean cancelled = "cancelled".equals(item.path("status").asString(""));
      Instant updated = IntegrationHttp.instant(item.path("updated").asString(null));
      long revision = updated == null ? clock.instant().toEpochMilli() : updated.toEpochMilli();
      entries.add(
          new SourcePage.Entry<>(id, revision, cancelled, cancelled ? null : event(item, user)));
    }
    String nextPage = body.path("nextPageToken").asString("");
    if (!nextPage.isBlank()) {
      return new SourcePage<>(
          entries, new Position(at.user(), nextPage).encode(), false, tokensJson(syncTokens));
    }
    String nextSync = body.path("nextSyncToken").asString("");
    if (!nextSync.isBlank()) {
      syncTokens.put(user, nextSync);
    }
    boolean last = at.user() + 1 >= users.size();
    return new SourcePage<>(
        entries,
        last ? "" : new Position(at.user() + 1, "").encode(),
        last,
        tokensJson(syncTokens));
  }

  private static CalendarEvent event(JsonNode item, String user) {
    String zone = item.path("start").path("timeZone").asString("UTC");
    Instant start = when(item.path("start"), zone, false);
    Instant end = when(item.path("end"), zone, true);
    if (start == null || end == null) {
      throw new SourceException(
          "PROVIDER_ERROR",
          "event " + item.path("id").asString("") + " has no start or end",
          false);
    }
    List<CalendarEvent.Attendee> attendees = new ArrayList<>();
    for (JsonNode a : item.path("attendees")) {
      attendees.add(
          new CalendarEvent.Attendee(
              a.path("email").asString(""), status(a.path("responseStatus").asString(""))));
    }
    String location = item.path("location").asString(null);
    String conferencing = item.path("hangoutLink").asString(null);
    if (conferencing == null) {
      for (JsonNode ep : item.path("conferenceData").path("entryPoints")) {
        if ("video".equals(ep.path("entryPointType").asString(""))) {
          conferencing = ep.path("uri").asString(null);
        }
      }
    }
    boolean virtual = conferencing != null && (location == null || location.isBlank());
    return new CalendarEvent(
        item.path("id").asString(""),
        item.path("recurringEventId").asString(null),
        item.path("summary").asString("(no title)"),
        item.path("description").asString(null),
        item.path("organizer").path("email").asString(user),
        attendees,
        start,
        end,
        zone,
        location == null ? null : new CalendarEvent.Location(location, null, "PHYSICAL"),
        conferencing,
        "CONFIRMED",
        virtual ? "VIRTUAL" : location != null ? "IN_PERSON" : "UNKNOWN");
  }

  private static @Nullable Instant when(JsonNode node, String zone, boolean end) {
    String dateTime = node.path("dateTime").asString(null);
    if (dateTime != null) {
      return IntegrationHttp.instant(dateTime);
    }
    String date = node.path("date").asString(null);
    if (date == null) {
      return null;
    }
    ZoneId z;
    try {
      z = ZoneId.of(zone);
    } catch (RuntimeException e) {
      z = ZoneId.of("UTC");
    }
    LocalDate d = LocalDate.parse(date);
    return (end ? d : d).atStartOfDay(z).toInstant();
  }

  private static String status(String google) {
    return switch (google) {
      case "accepted" -> "ACCEPTED";
      case "declined" -> "DECLINED";
      case "tentative" -> "TENTATIVE";
      default -> "NEEDS_ACTION";
    };
  }
}
