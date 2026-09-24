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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * Microsoft 365 calendars through Microsoft Graph's {@code calendarView/delta}, read as an
 * application (client credentials) for each covered user. Incremental: each user's
 * {@code @odata.deltaLink} is kept in the connector's watermark; {@code @odata.nextLink} is the
 * page cursor.
 */
public final class GraphCalendarSource extends LiveSource<CalendarEvent> implements CalendarSource {
  private final RestClient http;
  private final OAuth2Tokens tokens;
  private final IntegrationProperties.Microsoft props;
  private final Clock clock;

  public GraphCalendarSource(
      RestClient.Builder builder, IntegrationProperties.Microsoft props, Clock clock) {
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
    return "microsoft-365";
  }

  @Override
  public SourcePage<CalendarEvent> fetch(Connector connector, String since, String cursor) {
    List<String> users = users(connector);
    Map<String, String> deltaLinks = tokens(since);
    Position at = Position.parse(cursor);
    if (users.isEmpty() || at.user() >= users.size()) {
      return new SourcePage<>(List.of(), "", true, tokensJson(deltaLinks));
    }
    String user = users.get(at.user());
    String token;
    try {
      token =
          tokens.clientCredentials(
              "graph",
              props.loginUrl() + "/" + props.tenantId() + "/oauth2/v2.0/token",
              props.clientId(),
              props.clientSecret(),
              props.baseUrl() + "/.default");
    } catch (RuntimeException e) {
      throw IntegrationHttp.failure(provider(), e);
    }
    String url;
    if (!at.page().isBlank()) {
      url = at.page();
    } else if (deltaLinks.containsKey(user)) {
      url = deltaLinks.get(user);
    } else {
      Instant now = clock.instant();
      url =
          props.baseUrl()
              + "/v1.0/users/"
              + user
              + "/calendarView/delta?startDateTime="
              + now.minus(Duration.ofDays(7))
              + "&endDateTime="
              + now.plus(Duration.ofDays(180));
    }
    JsonNode body;
    try {
      body =
          IntegrationHttp.parse(
              http.get()
                  .uri(java.net.URI.create(url))
                  .header("Authorization", "Bearer " + token)
                  .header("Prefer", "outlook.timezone=\"UTC\"")
                  .retrieve()
                  .body(String.class));
    } catch (RuntimeException e) {
      throw IntegrationHttp.failure(provider(), e);
    }
    List<SourcePage.Entry<CalendarEvent>> entries = new ArrayList<>();
    for (JsonNode item : body.path("value")) {
      String id = item.path("id").asString("");
      if (id.isBlank()) {
        continue;
      }
      boolean removed = item.has("@removed") || item.path("isCancelled").asBoolean(false);
      Instant modified = IntegrationHttp.instant(item.path("lastModifiedDateTime").asString(null));
      long revision = modified == null ? clock.instant().toEpochMilli() : modified.toEpochMilli();
      entries.add(
          new SourcePage.Entry<>(id, revision, removed, removed ? null : event(item, user)));
    }
    String next = body.path("@odata.nextLink").asString("");
    if (!next.isBlank()) {
      return new SourcePage<>(
          entries, new Position(at.user(), next).encode(), false, tokensJson(deltaLinks));
    }
    String delta = body.path("@odata.deltaLink").asString("");
    if (!delta.isBlank()) {
      deltaLinks.put(user, delta);
    }
    boolean last = at.user() + 1 >= users.size();
    return new SourcePage<>(
        entries,
        last ? "" : new Position(at.user() + 1, "").encode(),
        last,
        tokensJson(deltaLinks));
  }

  private static CalendarEvent event(JsonNode item, String user) {
    Instant start = IntegrationHttp.instant(item.path("start").path("dateTime").asString(null));
    Instant end = IntegrationHttp.instant(item.path("end").path("dateTime").asString(null));
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
              a.path("emailAddress").path("address").asString(""),
              status(a.path("status").path("response").asString(""))));
    }
    String location = item.path("location").path("displayName").asString(null);
    if (location != null && location.isBlank()) {
      location = null;
    }
    String join = item.path("onlineMeeting").path("joinUrl").asString(null);
    boolean online = item.path("isOnlineMeeting").asBoolean(false);
    String zone = item.path("start").path("timeZone").asString("UTC");
    return new CalendarEvent(
        item.path("id").asString(""),
        item.path("seriesMasterId").asString(null),
        item.path("subject").asString("(no subject)"),
        item.path("bodyPreview").asString(null),
        item.path("organizer").path("emailAddress").path("address").asString(user),
        attendees,
        start,
        end,
        zone,
        location == null ? null : new CalendarEvent.Location(location, null, "PHYSICAL"),
        join,
        "CONFIRMED",
        online && location == null ? "VIRTUAL" : location != null ? "IN_PERSON" : "UNKNOWN");
  }

  private static String status(String graph) {
    return switch (graph) {
      case "accepted", "organizer" -> "ACCEPTED";
      case "declined" -> "DECLINED";
      case "tentativelyAccepted" -> "TENTATIVE";
      default -> "NEEDS_ACTION";
    };
  }
}
