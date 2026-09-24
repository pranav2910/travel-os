package io.travelos.context.source.live;

import io.travelos.context.IntegrationProperties;
import io.travelos.context.model.Connector;
import io.travelos.context.model.ConnectorKind;
import io.travelos.context.source.CrmRecord;
import io.travelos.context.source.CrmSource;
import io.travelos.context.source.SourcePage;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * Salesforce through the REST API with a connected app's client-credentials flow: scheduled
 * customer events (the {@code Event} object) changed since the watermark, in SOQL, paged by {@code
 * nextRecordsUrl}. Only an event with a physical location is an on-site visit.
 */
public final class SalesforceCrmSource extends LiveSource<CrmRecord> implements CrmSource {
  private final RestClient http;
  private final OAuth2Tokens tokens;
  private final IntegrationProperties.Salesforce props;
  private final Clock clock;

  public SalesforceCrmSource(
      RestClient.Builder builder, IntegrationProperties.Salesforce props, Clock clock) {
    this.http = builder.clone().build();
    this.tokens = new OAuth2Tokens(builder.clone().build(), clock);
    this.props = props;
    this.clock = clock;
  }

  @Override
  public ConnectorKind kind() {
    return ConnectorKind.CRM;
  }

  @Override
  public String provider() {
    return "salesforce";
  }

  @Override
  public SourcePage<CrmRecord> fetch(Connector connector, String since, String cursor) {
    String token;
    try {
      token =
          tokens.clientCredentials(
              "salesforce",
              props.instanceUrl() + "/services/oauth2/token",
              props.clientId(),
              props.clientSecret(),
              null);
    } catch (RuntimeException e) {
      throw IntegrationHttp.failure(provider(), e);
    }
    Instant from = IntegrationHttp.instant(since);
    if (from == null) {
      from = clock.instant().minus(java.time.Duration.ofDays(30));
    }
    String url;
    if (cursor != null && !cursor.isBlank()) {
      url = props.instanceUrl() + cursor;
    } else {
      String soql =
          "SELECT Id, Subject, Description, Location, StartDateTime, EndDateTime, IsDeleted, LastModifiedDate, Owner.Email,"
              + " Account.Name, Account.BillingCity, WhatId FROM Event WHERE LastModifiedDate > "
              + from
              + " ORDER BY LastModifiedDate ASC LIMIT 200";
      url =
          props.instanceUrl()
              + "/services/data/"
              + props.apiVersion()
              + "/query?q="
              + URLEncoder.encode(soql, StandardCharsets.UTF_8);
    }
    JsonNode body;
    try {
      body =
          IntegrationHttp.parse(
              http.get()
                  .uri(java.net.URI.create(url))
                  .header("Authorization", "Bearer " + token)
                  .retrieve()
                  .body(String.class));
    } catch (RuntimeException e) {
      throw IntegrationHttp.failure(provider(), e);
    }
    List<SourcePage.Entry<CrmRecord>> entries = new ArrayList<>();
    Instant watermark = from;
    for (JsonNode r : body.path("records")) {
      String id = r.path("Id").asString("");
      if (id.isBlank()) {
        continue;
      }
      Instant modified = IntegrationHttp.instant(r.path("LastModifiedDate").asString(null));
      if (modified != null && modified.isAfter(watermark)) {
        watermark = modified;
      }
      boolean deleted = r.path("IsDeleted").asBoolean(false);
      long revision = modified == null ? clock.instant().toEpochMilli() : modified.toEpochMilli();
      entries.add(new SourcePage.Entry<>(id, revision, deleted, deleted ? null : record(r)));
    }
    String next = body.path("nextRecordsUrl").asString("");
    boolean done = body.path("done").asBoolean(true) && next.isBlank();
    return new SourcePage<>(entries, done ? "" : next, done, watermark.toString());
  }

  private static CrmRecord record(JsonNode r) {
    String location = r.path("Location").asString(null);
    boolean onSite =
        location != null
            && !location.isBlank()
            && !location
                .toLowerCase(java.util.Locale.ROOT)
                .matches(".*(http|zoom|teams|meet\\.google|webex|virtual|phone|call).*");
    Instant start = IntegrationHttp.instant(r.path("StartDateTime").asString(null));
    Instant end = IntegrationHttp.instant(r.path("EndDateTime").asString(null));
    return new CrmRecord(
        r.path("Id").asString(""),
        "VISIT",
        r.path("Owner").path("Email").asString(""),
        r.path("Account").path("Name").asString(null),
        r.path("Account").path("BillingCity").asString(null),
        start,
        end,
        "UTC",
        onSite,
        "SCHEDULED",
        null,
        null,
        null,
        r.path("Description").asString(null));
  }
}
