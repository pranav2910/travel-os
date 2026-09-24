package io.travelos.audit.report;

import io.travelos.audit.store.AuditRepository;
import io.travelos.common.tenant.TenantId;
import io.travelos.spring.web.error.ApiException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase 9: the reports, as plain SQL over trip_fact (spend, outcomes) and audit_event (exceptions,
 * suppliers). Every number is a count or a sum of what the events said; nothing is estimated.
 * Periods are half-open [from, to) on the trip's booking or creation time (spend, outcomes) or the
 * event's time (exceptions, suppliers).
 */
@Service
public class ReportService {
  private final JdbcClient jdbc;

  public ReportService(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public record Period(Instant from, Instant to) {}

  public record SpendRow(
      String key,
      long trips,
      String currency,
      long bookedMinor,
      long capturedMinor,
      long refundedMinor,
      long creditMinor,
      long incrementalMinor,
      long netMinor) {}

  @Transactional(readOnly = true)
  public List<SpendRow> spend(TenantId tenant, Period p, String groupBy) {
    String column =
        switch (groupBy) {
          case "costCenter" -> "cost_center_id";
          case "project" -> "project_id";
          case "department" -> "department_id";
          case "legalEntity" -> "legal_entity_id";
          case "office" -> "office_id";
          case "traveler" -> "traveler_id";
          case "destination" -> "destination";
          case "month" -> "to_char(booked_at AT TIME ZONE 'UTC', 'YYYY-MM')";
          default ->
              throw new ApiException.Unprocessable(
                  "GROUP_BY_UNKNOWN", "groupBy must be one of " + ReportFacts.groupings());
        };
    return jdbc.sql(
            "SELECT COALESCE("
                + column
                + ", '(none)') AS k, COUNT(*) AS trips, COALESCE(currency, '') AS currency,"
                + " SUM(booked_minor) AS booked, SUM(captured_minor) AS captured, SUM(refunded_minor) AS refunded,"
                + " SUM(credit_minor) AS credit, SUM(incremental_minor) AS incremental"
                + " FROM trip_fact WHERE tenant_id = :t AND booked_at IS NOT NULL AND booked_at >= :from AND booked_at < :to"
                + " GROUP BY 1, 3 ORDER BY booked DESC, 1")
        .param("t", tenant.value())
        .param("from", Timestamp.from(p.from()))
        .param("to", Timestamp.from(p.to()))
        .query(
            (rs, i) ->
                new SpendRow(
                    rs.getString("k"),
                    rs.getLong("trips"),
                    rs.getString("currency").trim(),
                    rs.getLong("booked"),
                    rs.getLong("captured"),
                    rs.getLong("refunded"),
                    rs.getLong("credit"),
                    rs.getLong("incremental"),
                    rs.getLong("captured") + rs.getLong("incremental") - rs.getLong("refunded")))
        .list();
  }

  public record Outcomes(
      long created,
      long booked,
      long cancelled,
      long failed,
      long completed,
      @Nullable Double bookingRate,
      @Nullable Double medianHoursToBook,
      long disruptions,
      long recoveriesResolved,
      long recoveriesFailed,
      long autonomousRecoveries,
      @Nullable Double avgRecoverySeconds,
      long advisories,
      long checkins) {}

  @Transactional(readOnly = true)
  public Outcomes outcomes(TenantId tenant, Period p) {
    Map<String, Object> t =
        jdbc.sql(
                "SELECT COUNT(*) FILTER (WHERE created_at IS NOT NULL) AS created, COUNT(*) FILTER (WHERE booked_at IS NOT NULL) AS booked,"
                    + " COUNT(*) FILTER (WHERE status = 'CANCELLED') AS cancelled, COUNT(*) FILTER (WHERE status = 'FAILED') AS failed,"
                    + " COUNT(*) FILTER (WHERE status = 'COMPLETED') AS completed,"
                    + " percentile_cont(0.5) WITHIN GROUP (ORDER BY EXTRACT(EPOCH FROM (booked_at - created_at)) / 3600.0) FILTER (WHERE booked_at IS NOT NULL AND created_at IS NOT NULL) AS median_hours"
                    + " FROM trip_fact WHERE tenant_id = :t AND COALESCE(created_at, booked_at) >= :from AND COALESCE(created_at, booked_at) < :to")
            .param("t", tenant.value())
            .param("from", Timestamp.from(p.from()))
            .param("to", Timestamp.from(p.to()))
            .query()
            .singleRow();
    Map<String, Long> d =
        counts(
            tenant,
            p,
            List.of(
                "travel.disruption.impact-confirmed",
                "travel.disruption.resolved",
                "travel.disruption.recovery-failed",
                "travel.assistance.advisory-issued",
                "travel.assistance.checkin-recorded"));
    long autonomous =
        count(
            jdbc.sql(
                    "SELECT COUNT(*) FROM audit_event WHERE tenant_id = :t AND event_type = 'travel.disruption.resolved' AND data->>'autonomyOutcome' = 'ALLOW' AND occurred_at >= :from AND occurred_at < :to")
                .param("t", tenant.value())
                .param("from", Timestamp.from(p.from()))
                .param("to", Timestamp.from(p.to())));
    Double avgRecovery =
        average(
            jdbc.sql(
                    "SELECT AVG((data->>'durationMs')::numeric) / 1000.0 AS v FROM audit_event WHERE tenant_id = :t AND event_type = 'travel.disruption.resolved' AND jsonb_exists(data, 'durationMs') AND occurred_at >= :from AND occurred_at < :to")
                .param("t", tenant.value())
                .param("from", Timestamp.from(p.from()))
                .param("to", Timestamp.from(p.to())));
    long created = number(t.get("created"));
    long booked = number(t.get("booked"));
    Object median = t.get("median_hours");
    return new Outcomes(
        created,
        booked,
        number(t.get("cancelled")),
        number(t.get("failed")),
        number(t.get("completed")),
        created == 0 ? null : (double) booked / created,
        median == null ? null : ((Number) median).doubleValue(),
        d.getOrDefault("travel.disruption.impact-confirmed", 0L),
        d.getOrDefault("travel.disruption.resolved", 0L),
        d.getOrDefault("travel.disruption.recovery-failed", 0L),
        autonomous,
        avgRecovery,
        d.getOrDefault("travel.assistance.advisory-issued", 0L),
        d.getOrDefault("travel.assistance.checkin-recorded", 0L));
  }

  public record CountRow(String key, long count) {}

  public record Exceptions(
      List<CountRow> policyViolationsByReason,
      long approvalsRequested,
      long approvalsApproved,
      long approvalsRejected,
      long approvalsEscalated,
      long approvalsExpired,
      @Nullable Double avgApprovalHours,
      long budgetExceeded,
      List<CountRow> casesByKind,
      List<CountRow> casesByQueue,
      long casesResolved,
      @Nullable Double avgCaseResolutionHours,
      long notificationsFailed,
      long exposures) {}

  @Transactional(readOnly = true)
  public Exceptions exceptions(TenantId tenant, Period p) {
    List<CountRow> violations =
        jdbc.sql(
                "SELECT r->>'code' AS k, COUNT(*) AS n FROM audit_event e, jsonb_array_elements(e.data->'reasons') r"
                    + " WHERE e.tenant_id = :t AND e.event_type = 'travel.policy.violation' AND e.occurred_at >= :from AND e.occurred_at < :to GROUP BY 1 ORDER BY n DESC, 1")
            .param("t", tenant.value())
            .param("from", Timestamp.from(p.from()))
            .param("to", Timestamp.from(p.to()))
            .query((rs, i) -> new CountRow(rs.getString("k"), rs.getLong("n")))
            .list();
    Map<String, Long> c =
        counts(
            tenant,
            p,
            List.of(
                "travel.approval.requested",
                "travel.approval.approved",
                "travel.approval.rejected",
                "travel.approval.escalated",
                "travel.approval.expired",
                "travel.assistance.case-resolved",
                "travel.order.compensation-failed"));
    long budget =
        count(
            jdbc.sql(
                    "SELECT COUNT(*) FROM audit_event e, jsonb_array_elements(e.data->'reasons') r WHERE e.tenant_id = :t AND e.event_type IN ('travel.policy.evaluated', 'travel.policy.violation') AND r->>'code' = 'BUDGET_EXCEEDED' AND e.occurred_at >= :from AND e.occurred_at < :to")
                .param("t", tenant.value())
                .param("from", Timestamp.from(p.from()))
                .param("to", Timestamp.from(p.to())));
    Double avgApproval =
        average(
            jdbc.sql(
                    "SELECT AVG(EXTRACT(EPOCH FROM (d.occurred_at - r.occurred_at)) / 3600.0) AS v FROM audit_event r JOIN audit_event d"
                        + " ON d.tenant_id = r.tenant_id AND d.data->>'approvalId' = r.data->>'approvalId' AND d.event_type IN ('travel.approval.approved', 'travel.approval.rejected')"
                        + " WHERE r.tenant_id = :t AND r.event_type = 'travel.approval.requested' AND r.occurred_at >= :from AND r.occurred_at < :to")
                .param("t", tenant.value())
                .param("from", Timestamp.from(p.from()))
                .param("to", Timestamp.from(p.to())));
    List<CountRow> byKind = grouped(tenant, p, "travel.assistance.case-opened", "kind");
    List<CountRow> byQueue = grouped(tenant, p, "travel.assistance.case-opened", "queue");
    Double avgCase =
        average(
            jdbc.sql(
                    "SELECT AVG(EXTRACT(EPOCH FROM (d.occurred_at - o.occurred_at)) / 3600.0) AS v FROM audit_event o JOIN audit_event d"
                        + " ON d.tenant_id = o.tenant_id AND d.data->>'caseId' = o.data->>'caseId' AND d.event_type = 'travel.assistance.case-resolved'"
                        + " WHERE o.tenant_id = :t AND o.event_type = 'travel.assistance.case-opened' AND o.occurred_at >= :from AND o.occurred_at < :to")
                .param("t", tenant.value())
                .param("from", Timestamp.from(p.from()))
                .param("to", Timestamp.from(p.to())));
    long failedNotifications =
        count(
            jdbc.sql(
                    "SELECT COUNT(*) FROM audit_event WHERE tenant_id = :t AND event_type = 'travel.assistance.notification-sent' AND data->>'status' = 'FAILED' AND occurred_at >= :from AND occurred_at < :to")
                .param("t", tenant.value())
                .param("from", Timestamp.from(p.from()))
                .param("to", Timestamp.from(p.to())));
    return new Exceptions(
        violations,
        c.getOrDefault("travel.approval.requested", 0L),
        c.getOrDefault("travel.approval.approved", 0L),
        c.getOrDefault("travel.approval.rejected", 0L),
        c.getOrDefault("travel.approval.escalated", 0L),
        c.getOrDefault("travel.approval.expired", 0L),
        avgApproval,
        budget,
        byKind,
        byQueue,
        c.getOrDefault("travel.assistance.case-resolved", 0L),
        avgCase,
        failedNotifications,
        c.getOrDefault("travel.order.compensation-failed", 0L));
  }

  public record SupplierRow(
      String provider,
      long ordersConfirmed,
      long itemsConfirmed,
      long itemsReleased,
      long cancellationsRefused,
      long disruptions,
      long creditsIssued,
      long creditMinor) {}

  @Transactional(readOnly = true)
  public List<SupplierRow> suppliers(TenantId tenant, Period p) {
    Map<String, long[]> rows = new LinkedHashMap<>();
    java.util.function.BiConsumer<String, Integer> bump =
        (provider, idx) -> rows.computeIfAbsent(provider, k -> new long[7])[idx]++;
    for (Map<String, Object> r : events(tenant, p, "travel.order.confirmed")) {
      String supplier = String.valueOf(r.getOrDefault("supplier", "(unknown)"));
      bump.accept(supplier, 0);
      for (Object item : list(r.get("items"))) {
        if (item instanceof Map<?, ?> m) {
          bump.accept(field(m, "provider", supplier), 1);
        }
      }
    }
    for (Map<String, Object> r : events(tenant, p, "travel.order.items-released")) {
      for (Object item : list(r.get("items"))) {
        if (item instanceof Map<?, ?> m) {
          bump.accept(field(m, "provider", "(unknown)"), 2);
        }
      }
      for (Object x : list(r.get("refused"))) {
        if (x instanceof Map<?, ?> m) {
          bump.accept(field(m, "provider", "(unknown)"), 3);
        }
      }
    }
    for (Map<String, Object> r : events(tenant, p, "travel.order.compensation-failed")) {
      for (Object x : list(r.get("exposures"))) {
        if (x instanceof Map<?, ?> m) {
          bump.accept(field(m, "provider", "(unknown)"), 3);
        }
      }
    }
    for (Map<String, Object> r : events(tenant, p, "travel.disruption.impact-confirmed")) {
      bump.accept(String.valueOf(r.getOrDefault("supplier", "(unknown)")), 4);
    }
    for (Map<String, Object> r : events(tenant, p, "travel.finance.credit-issued")) {
      String provider = String.valueOf(r.getOrDefault("provider", "(unknown)"));
      bump.accept(provider, 5);
      if (r.get("amount") instanceof Map<?, ?> m && m.get("amountMinor") != null) {
        rows.get(provider)[6] += ((Number) m.get("amountMinor")).longValue();
      }
    }
    List<SupplierRow> out = new ArrayList<>();
    rows.forEach((k, v) -> out.add(new SupplierRow(k, v[0], v[1], v[2], v[3], v[4], v[5], v[6])));
    out.sort((a, b) -> Long.compare(b.ordersConfirmed(), a.ordersConfirmed()));
    return out;
  }

  private Map<String, Long> counts(TenantId tenant, Period p, List<String> types) {
    Map<String, Long> out = new LinkedHashMap<>();
    jdbc.sql(
            "SELECT event_type, COUNT(*) AS n FROM audit_event WHERE tenant_id = :t AND event_type IN (:types) AND occurred_at >= :from AND occurred_at < :to GROUP BY event_type")
        .param("t", tenant.value())
        .param("types", types)
        .param("from", Timestamp.from(p.from()))
        .param("to", Timestamp.from(p.to()))
        .query((rs, i) -> Map.entry(rs.getString("event_type"), rs.getLong("n")))
        .list()
        .forEach(e -> out.put(e.getKey(), e.getValue()));
    return out;
  }

  private List<CountRow> grouped(TenantId tenant, Period p, String type, String field) {
    return jdbc.sql(
            "SELECT COALESCE(data->>'"
                + field
                + "', '(none)') AS k, COUNT(*) AS n FROM audit_event WHERE tenant_id = :t AND event_type = :type AND occurred_at >= :from AND occurred_at < :to GROUP BY 1 ORDER BY n DESC, 1")
        .param("t", tenant.value())
        .param("type", type)
        .param("from", Timestamp.from(p.from()))
        .param("to", Timestamp.from(p.to()))
        .query((rs, i) -> new CountRow(rs.getString("k"), rs.getLong("n")))
        .list();
  }

  private List<Map<String, Object>> events(TenantId tenant, Period p, String type) {
    return jdbc.sql(
            "SELECT data FROM audit_event WHERE tenant_id = :t AND event_type = :type AND occurred_at >= :from AND occurred_at < :to ORDER BY occurred_at")
        .param("t", tenant.value())
        .param("type", type)
        .param("from", Timestamp.from(p.from()))
        .param("to", Timestamp.from(p.to()))
        .query((rs, i) -> AuditRepository.data(rs.getString("data")))
        .list();
  }

  private static long count(JdbcClient.StatementSpec spec) {
    return spec.query(Long.class).single();
  }

  private static @Nullable Double average(JdbcClient.StatementSpec spec) {
    java.math.BigDecimal v = spec.query(java.math.BigDecimal.class).optional().orElse(null);
    return v == null ? null : v.doubleValue();
  }

  private static String field(Map<?, ?> m, String key, String fallback) {
    Object v = m.get(key);
    return v == null ? fallback : String.valueOf(v);
  }

  private static long number(@Nullable Object v) {
    return v instanceof Number n ? n.longValue() : 0L;
  }

  private static List<?> list(@Nullable Object v) {
    return v instanceof List<?> l ? l : List.of();
  }
}
