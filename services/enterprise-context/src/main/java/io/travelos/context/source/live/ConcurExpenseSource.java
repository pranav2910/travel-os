package io.travelos.context.source.live;

import io.travelos.context.IntegrationProperties;
import io.travelos.context.model.Connector;
import io.travelos.context.model.ConnectorKind;
import io.travelos.context.source.ExpenseRecord;
import io.travelos.context.source.ExpenseSource;
import io.travelos.context.source.SourcePage;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * SAP Concur expense reports through the v3 Expense Reports API, authorized by a company refresh
 * token (OAuth 2.0). Reports modified since the watermark, paged by offset; every report is a
 * TRIP_REPORT expense record for enrichment and duplicate detection, never a trip.
 */
public final class ConcurExpenseSource extends LiveSource<ExpenseRecord> implements ExpenseSource {
  private final RestClient http;
  private final OAuth2Tokens tokens;
  private final IntegrationProperties.Concur props;
  private final Clock clock;

  public ConcurExpenseSource(
      RestClient.Builder builder, IntegrationProperties.Concur props, Clock clock) {
    this.http = builder.clone().build();
    this.tokens = new OAuth2Tokens(builder.clone().build(), clock);
    this.props = props;
    this.clock = clock;
  }

  @Override
  public ConnectorKind kind() {
    return ConnectorKind.EXPENSE;
  }

  @Override
  public String provider() {
    return "sap-concur";
  }

  @Override
  public SourcePage<ExpenseRecord> fetch(Connector connector, String since, String cursor) {
    String token;
    try {
      token =
          tokens.refresh(
              "concur",
              props.baseUrl() + "/oauth2/v0/token",
              props.clientId(),
              props.clientSecret(),
              props.refreshToken());
    } catch (RuntimeException e) {
      throw IntegrationHttp.failure(provider(), e);
    }
    Instant from = IntegrationHttp.instant(since);
    if (from == null) {
      from = clock.instant().minus(java.time.Duration.ofDays(90));
    }
    String url =
        (cursor != null && !cursor.isBlank() && cursor.startsWith("http"))
            ? cursor
            : props.baseUrl()
                + "/api/v3.0/expense/reports?limit=100&modifiedafter="
                + from.toString().replace("Z", "")
                + (cursor == null || cursor.isBlank() ? "" : "&offset=" + cursor);
    JsonNode body;
    try {
      body =
          IntegrationHttp.parse(
              http.get()
                  .uri(java.net.URI.create(url))
                  .header("Authorization", "Bearer " + token)
                  .header("Accept", "application/json")
                  .retrieve()
                  .body(String.class));
    } catch (RuntimeException e) {
      throw IntegrationHttp.failure(provider(), e);
    }
    List<SourcePage.Entry<ExpenseRecord>> entries = new ArrayList<>();
    Instant watermark = from;
    for (JsonNode r : body.path("Items")) {
      String id = r.path("ID").asString("");
      if (id.isBlank()) {
        continue;
      }
      Instant modified = IntegrationHttp.instant(r.path("LastModifiedDate").asString(null));
      if (modified != null && modified.isAfter(watermark)) {
        watermark = modified;
      }
      long revision = modified == null ? clock.instant().toEpochMilli() : modified.toEpochMilli();
      entries.add(new SourcePage.Entry<>(id, revision, false, record(r, modified)));
    }
    String next = body.path("NextPage").asString("");
    boolean done = next.isBlank();
    return new SourcePage<>(entries, done ? "" : next, done, watermark.toString());
  }

  private ExpenseRecord record(JsonNode r, Instant modified) {
    LocalDate day =
        (modified == null ? clock.instant() : modified).atZone(ZoneOffset.UTC).toLocalDate();
    LocalDate start = date(r.path("UserDefinedDate").asString(null), day);
    LocalDate end = date(r.path("SubmitDate").asString(null), start);
    BigDecimal total = new BigDecimal(r.path("Total").asString("0"));
    return new ExpenseRecord(
        r.path("ID").asString(""),
        r.path("OwnerLoginID").asString(""),
        "TRIP_REPORT",
        r.path("Country").isMissingNode() ? null : null,
        start,
        end.isBefore(start) ? start : end,
        total.movePointRight(2).longValue(),
        r.path("CurrencyCode").asString("USD"),
        r.path("Name").asString(null),
        null);
  }

  private static LocalDate date(String iso, LocalDate fallback) {
    if (iso == null || iso.isBlank()) {
      return fallback;
    }
    try {
      return LocalDate.parse(iso.substring(0, 10));
    } catch (RuntimeException e) {
      return fallback;
    }
  }
}
