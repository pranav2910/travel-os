package io.travelos.context.source.live;

import io.travelos.context.model.Connector;
import io.travelos.context.source.EnterpriseSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/** Shared plumbing for the genuine adapters: never simulated; connector config as JSON. */
abstract class LiveSource<T> implements EnterpriseSource<T> {
  @Override
  public boolean simulated() {
    return false;
  }

  /** The connector's configuration (no secrets in it, ConnectorService refuses them). */
  static JsonNode config(Connector connector) {
    return IntegrationHttp.parse(connector.configJson());
  }

  /** The people whose calendars a connector covers ({@code {"users": ["a@x", "b@x"]}}). */
  static List<String> users(Connector connector) {
    List<String> out = new ArrayList<>();
    config(connector).path("users").forEach(u -> out.add(u.asString()));
    return out;
  }

  /** Per-user checkpoints kept in the connector's watermark as JSON ({@code {"a@x": "token"}}). */
  static Map<String, String> tokens(String since) {
    if (since == null || since.isBlank() || !since.startsWith("{")) {
      return new java.util.LinkedHashMap<>();
    }
    Map<String, String> out = new java.util.LinkedHashMap<>();
    IntegrationHttp.parse(since)
        .properties()
        .forEach(e -> out.put(e.getKey(), e.getValue().asString()));
    return out;
  }

  static String tokensJson(Map<String, String> tokens) {
    return IntegrationHttp.JSON.writeValueAsString(tokens);
  }

  /** Cursor {@code u:<user index>|p:<page token>} for the multi-user calendar adapters. */
  record Position(int user, String page) {
    static Position parse(String cursor) {
      if (cursor == null || cursor.isBlank()) {
        return new Position(0, "");
      }
      int bar = cursor.indexOf('|');
      int user = Integer.parseInt(cursor.substring(2, bar));
      return new Position(user, cursor.substring(bar + 3));
    }

    String encode() {
      return "u:" + user + "|p:" + page;
    }
  }
}
