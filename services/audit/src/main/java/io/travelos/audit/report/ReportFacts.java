package io.travelos.audit.report;

import io.travelos.common.tenant.TenantId;
import io.travelos.events.EventEnvelope;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Phase 9: keeps trip_fact in step with the events as the audit service stores them (in the same
 * transaction, after the exactly-once insert). Nothing is inferred: a captured payment is what
 * Finance captured, a booked total is what Travel Core reported.
 */
@Component
public class ReportFacts {
  private final JdbcClient jdbc;

  public ReportFacts(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public void apply(EventEnvelope event, Instant now) {
    Map<String, Object> d = event.data();
    String tripId = str(d.get("tripId"));
    if (tripId == null) {
      return;
    }
    TenantId tenant = TenantId.of(event.tenantId());
    Map<String, Object> set = new LinkedHashMap<>();
    Map<String, Long> add = new LinkedHashMap<>();
    switch (event.eventType()) {
      case "travel.trip.created" -> {
        set.put("traveler_id", str(d.get("travelerId")));
        set.put("created_at", Timestamp.from(event.occurredAt()));
        journey(d, set);
        allocation(d, set);
      }
      case "travel.trip.booked" -> {
        set.put("status", "BOOKED");
        set.put("booked_at", Timestamp.from(event.occurredAt()));
        money(d.get("total"), set, "booked_minor", add, null);
        journey(d, set);
        allocation(d, set);
      }
      case "travel.trip.cancelled" -> {
        set.put("status", "CANCELLED");
        set.put("ended_at", Timestamp.from(event.occurredAt()));
      }
      case "travel.trip.failed" -> {
        set.put("status", "FAILED");
        set.put("ended_at", Timestamp.from(event.occurredAt()));
      }
      case "travel.trip.completed" -> {
        set.put("status", "COMPLETED");
        set.put("ended_at", Timestamp.from(event.occurredAt()));
      }
      case "travel.finance.payment-captured" ->
          money(d.get("amount"), set, null, add, "captured_minor");
      case "travel.finance.payment-refunded" ->
          money(d.get("amount"), set, null, add, "refunded_minor");
      case "travel.finance.credit-issued" -> money(d.get("amount"), set, null, add, "credit_minor");
      case "travel.disruption.impact-confirmed" -> add.put("disruptions", 1L);
      case "travel.disruption.resolved" ->
          money(d.get("incrementalCost"), set, null, add, "incremental_minor");
      case "travel.disruption.recovery-failed" -> add.put("recoveries_failed", 1L);
      case "travel.policy.violation" -> add.put("policy_violations", 1L);
      case "travel.approval.requested" -> add.put("approvals_requested", 1L);
      case "travel.approval.rejected" -> add.put("approvals_rejected", 1L);
      case "travel.approval.escalated" -> add.put("approvals_escalated", 1L);
      case "travel.approval.expired" -> add.put("approvals_expired", 1L);
      case "travel.assistance.case-opened" -> add.put("cases_opened", 1L);
      default -> {
        return;
      }
    }
    upsert(tenant, tripId, set, add, now);
  }

  private void upsert(
      TenantId tenant, String tripId, Map<String, Object> set, Map<String, Long> add, Instant now) {
    StringBuilder cols = new StringBuilder("tenant_id, trip_id, updated_at");
    StringBuilder vals = new StringBuilder(":tenant, :trip, :now");
    StringBuilder update = new StringBuilder("updated_at = :now");
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("tenant", tenant.value());
    params.put("trip", tripId);
    params.put("now", Timestamp.from(now));
    for (Map.Entry<String, Object> e : set.entrySet()) {
      if (e.getValue() == null) {
        continue;
      }
      cols.append(", ").append(e.getKey());
      vals.append(", :").append(e.getKey());
      update.append(", ").append(e.getKey()).append(" = EXCLUDED.").append(e.getKey());
      params.put(e.getKey(), e.getValue());
    }
    for (Map.Entry<String, Long> e : add.entrySet()) {
      cols.append(", ").append(e.getKey());
      vals.append(", :").append(e.getKey());
      update
          .append(", ")
          .append(e.getKey())
          .append(" = trip_fact.")
          .append(e.getKey())
          .append(" + EXCLUDED.")
          .append(e.getKey());
      params.put(e.getKey(), e.getValue());
    }
    jdbc.sql(
            "INSERT INTO trip_fact ("
                + cols
                + ") VALUES ("
                + vals
                + ") ON CONFLICT (tenant_id, trip_id) DO UPDATE SET "
                + update)
        .params(params)
        .update();
  }

  private static void journey(Map<String, Object> d, Map<String, Object> set) {
    set.put("origin", str(d.get("origin")));
    set.put("destination", str(d.get("destination")));
    set.put("departs_at", instant(d.get("departsAt")));
    set.put("returns_at", instant(d.get("returnsAt")));
  }

  private static void allocation(Map<String, Object> d, Map<String, Object> set) {
    if (d.get("allocation") instanceof Map<?, ?> a) {
      set.put("department_id", str(a.get("departmentId")));
      set.put("cost_center_id", str(a.get("costCenterId")));
      set.put("project_id", str(a.get("projectId")));
      set.put("legal_entity_id", str(a.get("legalEntityId")));
      set.put("office_id", str(a.get("officeId")));
    }
  }

  private static void money(
      @Nullable Object value,
      Map<String, Object> set,
      @Nullable String setColumn,
      Map<String, Long> add,
      @Nullable String addColumn) {
    if (value instanceof Map<?, ?> m && m.get("currency") != null && m.get("amountMinor") != null) {
      long minor = ((Number) m.get("amountMinor")).longValue();
      set.put("currency", String.valueOf(m.get("currency")));
      if (setColumn != null) {
        set.put(setColumn, minor);
      }
      if (addColumn != null) {
        add.put(addColumn, minor);
      }
    }
  }

  private static @Nullable Timestamp instant(@Nullable Object value) {
    String s = str(value);
    if (s == null) {
      return null;
    }
    try {
      return Timestamp.from(Instant.parse(s));
    } catch (RuntimeException e) {
      return null;
    }
  }

  private static @Nullable String str(@Nullable Object value) {
    if (value == null) {
      return null;
    }
    String s = String.valueOf(value);
    return s.isBlank() ? null : s;
  }

  static List<String> groupings() {
    return List.of(
        "costCenter",
        "project",
        "department",
        "legalEntity",
        "office",
        "traveler",
        "destination",
        "month");
  }
}
