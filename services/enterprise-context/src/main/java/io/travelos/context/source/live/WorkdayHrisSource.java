package io.travelos.context.source.live;

import io.travelos.context.IntegrationProperties;
import io.travelos.context.model.Connector;
import io.travelos.context.model.ConnectorKind;
import io.travelos.context.source.HrisRecord;
import io.travelos.context.source.HrisSource;
import io.travelos.context.source.SourcePage;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * Workday Report-as-a-Service (RaaS): a custom report of active and recently terminated workers,
 * answered as JSON under basic authentication for an integration system user. The report is read
 * whole on every run (RaaS has no change feed); the watermark is the run's time and each worker's
 * revision is that time too, so an unchanged worker lands on the same row.
 */
public final class WorkdayHrisSource extends LiveSource<HrisRecord> implements HrisSource {
  private final RestClient http;
  private final IntegrationProperties.Workday props;
  private final Clock clock;

  public WorkdayHrisSource(
      RestClient.Builder builder, IntegrationProperties.Workday props, Clock clock) {
    this.http =
        builder
            .clone()
            .defaultHeaders(
                h ->
                    h.setBasicAuth(
                        props.username() == null ? "" : props.username(),
                        props.password() == null ? "" : props.password()))
            .build();
    this.props = props;
    this.clock = clock;
  }

  @Override
  public ConnectorKind kind() {
    return ConnectorKind.HRIS;
  }

  @Override
  public String provider() {
    return "workday";
  }

  @Override
  public SourcePage<HrisRecord> fetch(Connector connector, String since, String cursor) {
    Instant now = clock.instant();
    JsonNode report;
    try {
      String url =
          props.reportUrl() + (props.reportUrl().contains("?") ? "&" : "?") + "format=json";
      report =
          IntegrationHttp.parse(
              http.get()
                  .uri(java.net.URI.create(url))
                  .header("Accept", "application/json")
                  .retrieve()
                  .body(String.class));
    } catch (RuntimeException e) {
      throw IntegrationHttp.failure(provider(), e);
    }
    List<SourcePage.Entry<HrisRecord>> entries = new ArrayList<>();
    for (JsonNode row : report.path("Report_Entry")) {
      String id = first(row, "Employee_ID", "employeeId", "Worker_ID");
      if (id == null) {
        continue;
      }
      boolean active = truthy(first(row, "Active_Status", "active", "Active"));
      HrisRecord record =
          new HrisRecord(
              id,
              orElse(
                  first(row, "primaryWorkEmail", "Email", "Work_Email"), id + "@unknown.invalid"),
              orElse(first(row, "Legal_Name", "Name", "Full_Name"), id),
              iata(first(row, "Location_IATA", "Location", "Work_Location")),
              orElse(first(row, "Time_Zone", "timeZone"), "UTC"),
              first(row, "Manager_ID", "managerId"),
              active,
              first(row, "Supervisory_Organization_ID", "Department_ID"),
              first(row, "Cost_Center_ID", "costCenter"),
              first(row, "Company_ID", "Legal_Entity_ID"),
              first(row, "Location_ID", "officeId"));
      entries.add(new SourcePage.Entry<>(id, now.toEpochMilli(), false, record));
    }
    return new SourcePage<>(entries, "", true, now.toString());
  }

  private static @Nullable String first(JsonNode row, String... keys) {
    for (String k : keys) {
      JsonNode v = row.get(k);
      if (v != null && !v.isNull() && !v.asString().isBlank()) {
        return v.asString();
      }
    }
    return null;
  }

  private static String orElse(@Nullable String v, String fallback) {
    return v == null ? fallback : v;
  }

  private static boolean truthy(@Nullable String v) {
    return v == null
        || v.equals("1")
        || v.equalsIgnoreCase("true")
        || v.equalsIgnoreCase("yes")
        || v.equalsIgnoreCase("active");
  }

  private static String iata(@Nullable String v) {
    return v != null && v.matches("^[A-Za-z]{3}$") ? v.toUpperCase(java.util.Locale.ROOT) : "UNK";
  }
}
