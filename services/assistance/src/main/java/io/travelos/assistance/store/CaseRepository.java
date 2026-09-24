package io.travelos.assistance.store;

import io.travelos.assistance.model.AssistanceCase;
import io.travelos.assistance.model.CaseEvent;
import io.travelos.assistance.model.CaseKind;
import io.travelos.assistance.model.CaseStatus;
import io.travelos.assistance.model.Priority;
import io.travelos.assistance.model.Queue;
import io.travelos.common.tenant.TenantId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class CaseRepository {
  private final JdbcClient jdbc;

  public CaseRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public void insert(AssistanceCase c) {
    jdbc.sql(
            """
            INSERT INTO assistance_case (case_id, tenant_id, kind, status, priority, queue, title, summary,
              trip_id, order_id, traveler_id, disruption_id, exposure_id, component_id, dedupe_key, owner,
              next_action, next_action_role, escalation_level, due_at, opened_at, updated_at, resolved_at,
              closed_at, resolution, source_event_id, source_event_type, version)
            VALUES (:id, :tenant, :kind, :status, :priority, :queue, :title, :summary, :trip, :order,
              :traveler, :disruption, :exposure, :component, :key, :owner, :next, :role, :level, :due,
              :opened, :updated, :resolved, :closed, :resolution, :srcId, :srcType, 0)
            """)
        .param("id", c.caseId())
        .param("tenant", c.tenant().value())
        .param("kind", c.kind().name())
        .param("status", c.status().name())
        .param("priority", c.priority().name())
        .param("queue", c.queue().name())
        .param("title", c.title())
        .param("summary", c.summary())
        .param("trip", c.tripId())
        .param("order", c.orderId())
        .param("traveler", c.travelerId())
        .param("disruption", c.disruptionId())
        .param("exposure", c.exposureId())
        .param("component", c.componentId())
        .param("key", c.dedupeKey())
        .param("owner", c.owner())
        .param("next", c.nextAction())
        .param("role", c.nextActionRole())
        .param("level", c.escalationLevel())
        .param("due", Rows.ts(c.dueAt()))
        .param("opened", Rows.ts(c.openedAt()))
        .param("updated", Rows.ts(c.updatedAt()))
        .param("resolved", Rows.ts(c.resolvedAt()))
        .param("closed", Rows.ts(c.closedAt()))
        .param("resolution", c.resolution())
        .param("srcId", c.sourceEventId())
        .param("srcType", c.sourceEventType())
        .update();
  }

  /** Writes every mutable column; refused (0 rows) when the version moved. */
  public boolean update(AssistanceCase c, long expectedVersion) {
    return jdbc.sql(
                """
                UPDATE assistance_case SET status = :status, priority = :priority, queue = :queue, owner = :owner,
                  next_action = :next, next_action_role = :role, escalation_level = :level, due_at = :due,
                  updated_at = :updated, resolved_at = :resolved, closed_at = :closed, resolution = :resolution,
                  summary = :summary, traveler_id = COALESCE(:traveler, traveler_id), version = version + 1
                WHERE case_id = :id AND version = :version
                """)
            .param("id", c.caseId())
            .param("version", expectedVersion)
            .param("status", c.status().name())
            .param("priority", c.priority().name())
            .param("queue", c.queue().name())
            .param("owner", c.owner())
            .param("next", c.nextAction())
            .param("role", c.nextActionRole())
            .param("level", c.escalationLevel())
            .param("due", Rows.ts(c.dueAt()))
            .param("updated", Rows.ts(c.updatedAt()))
            .param("resolved", Rows.ts(c.resolvedAt()))
            .param("closed", Rows.ts(c.closedAt()))
            .param("resolution", c.resolution())
            .param("summary", c.summary())
            .param("traveler", c.travelerId())
            .update()
        == 1;
  }

  public Optional<AssistanceCase> find(TenantId tenant, String caseId) {
    return jdbc.sql("SELECT * FROM assistance_case WHERE tenant_id = :t AND case_id = :id")
        .param("t", tenant.value())
        .param("id", caseId)
        .query(CaseRepository::map)
        .optional();
  }

  /** The row, locked for this transaction (a person and the sweep may race on one case). */
  public Optional<AssistanceCase> lock(TenantId tenant, String caseId) {
    return jdbc.sql(
            "SELECT * FROM assistance_case WHERE tenant_id = :t AND case_id = :id FOR UPDATE")
        .param("t", tenant.value())
        .param("id", caseId)
        .query(CaseRepository::map)
        .optional();
  }

  public Optional<AssistanceCase> findOpen(TenantId tenant, String dedupeKey) {
    return jdbc.sql(
            "SELECT * FROM assistance_case WHERE tenant_id = :t AND dedupe_key = :k AND status NOT IN ('RESOLVED', 'CLOSED') FOR UPDATE")
        .param("t", tenant.value())
        .param("k", dedupeKey)
        .query(CaseRepository::map)
        .optional();
  }

  /** Open cases matching a dedupe-key prefix (a trip's, a disruption's), locked. */
  public List<AssistanceCase> findOpenByPrefix(TenantId tenant, String prefix) {
    return jdbc.sql(
            "SELECT * FROM assistance_case WHERE tenant_id = :t AND dedupe_key LIKE :k AND status NOT IN ('RESOLVED', 'CLOSED') ORDER BY opened_at FOR UPDATE")
        .param("t", tenant.value())
        .param("k", prefix.replace("%", "\\%").replace("_", "\\_") + "%")
        .query(CaseRepository::map)
        .list();
  }

  public record Filter(
      @Nullable CaseStatus status,
      boolean openOnly,
      @Nullable Queue queue,
      @Nullable CaseKind kind,
      @Nullable String owner,
      @Nullable String tripId,
      @Nullable String travelerId,
      boolean overdueOnly,
      int limit) {}

  public List<AssistanceCase> list(TenantId tenant, Filter f, Instant now) {
    StringBuilder sql = new StringBuilder("SELECT * FROM assistance_case WHERE tenant_id = :t");
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("t", tenant.value());
    if (f.status() != null) {
      sql.append(" AND status = :status");
      params.put("status", f.status().name());
    } else if (f.openOnly()) {
      sql.append(" AND status NOT IN ('RESOLVED', 'CLOSED')");
    }
    if (f.queue() != null) {
      sql.append(" AND queue = :queue");
      params.put("queue", f.queue().name());
    }
    if (f.kind() != null) {
      sql.append(" AND kind = :kind");
      params.put("kind", f.kind().name());
    }
    if (f.owner() != null) {
      sql.append(" AND owner = :owner");
      params.put("owner", f.owner());
    }
    if (f.tripId() != null) {
      sql.append(" AND trip_id = :trip");
      params.put("trip", f.tripId());
    }
    if (f.travelerId() != null) {
      sql.append(" AND traveler_id = :traveler");
      params.put("traveler", f.travelerId());
    }
    if (f.overdueOnly()) {
      sql.append(" AND status NOT IN ('RESOLVED', 'CLOSED') AND due_at < :now");
      params.put("now", Rows.ts(now));
    }
    sql.append(
        " ORDER BY CASE priority WHEN 'CRITICAL' THEN 0 WHEN 'HIGH' THEN 1 WHEN 'NORMAL' THEN 2 ELSE 3 END, due_at, opened_at LIMIT :limit");
    params.put("limit", f.limit());
    return jdbc.sql(sql.toString()).params(params).query(CaseRepository::map).list();
  }

  /** Open cases past their due time, oldest first, locked so one sweep at a time escalates. */
  public List<AssistanceCase> overdue(Instant now, int limit) {
    return jdbc.sql(
            "SELECT * FROM assistance_case WHERE status NOT IN ('RESOLVED', 'CLOSED') AND due_at < :now ORDER BY due_at LIMIT :limit FOR UPDATE SKIP LOCKED")
        .param("now", Rows.ts(now))
        .param("limit", limit)
        .query(CaseRepository::map)
        .list();
  }

  public record Summary(String queue, String status, long count, long overdue) {}

  public List<Summary> summary(TenantId tenant, Instant now) {
    return jdbc.sql(
            """
            SELECT queue, status, COUNT(*) AS n, SUM(CASE WHEN due_at < :now AND status NOT IN ('RESOLVED', 'CLOSED') THEN 1 ELSE 0 END) AS overdue
            FROM assistance_case WHERE tenant_id = :t GROUP BY queue, status ORDER BY queue, status
            """)
        .param("t", tenant.value())
        .param("now", Rows.ts(now))
        .query(
            (rs, i) ->
                new Summary(
                    rs.getString("queue"),
                    rs.getString("status"),
                    rs.getLong("n"),
                    rs.getLong("overdue")))
        .list();
  }

  public void appendEvent(TenantId tenant, CaseEvent e) {
    jdbc.sql(
            """
            INSERT INTO case_event (case_event_id, case_id, tenant_id, kind, actor, message, data, occurred_at)
            VALUES (:id, :case, :tenant, :kind, :actor, :message, CAST(:data AS jsonb), :at)
            """)
        .param("id", e.caseEventId())
        .param("case", e.caseId())
        .param("tenant", tenant.value())
        .param("kind", e.kind().name())
        .param("actor", e.actor())
        .param("message", e.message())
        .param("data", Rows.json(e.data()))
        .param("at", Rows.ts(e.occurredAt()))
        .update();
  }

  public List<CaseEvent> events(TenantId tenant, String caseId) {
    return jdbc.sql(
            "SELECT * FROM case_event WHERE tenant_id = :t AND case_id = :id ORDER BY occurred_at, case_event_id")
        .param("t", tenant.value())
        .param("id", caseId)
        .query(
            (rs, i) ->
                new CaseEvent(
                    rs.getString("case_event_id"),
                    rs.getString("case_id"),
                    CaseEvent.Kind.valueOf(rs.getString("kind")),
                    rs.getString("actor"),
                    rs.getString("message"),
                    Rows.map(rs, "data"),
                    Rows.instantOrThrow(rs, "occurred_at")))
        .list();
  }

  static AssistanceCase map(ResultSet rs, int i) throws SQLException {
    return new AssistanceCase(
        rs.getString("case_id"),
        TenantId.of(rs.getString("tenant_id")),
        CaseKind.valueOf(rs.getString("kind")),
        CaseStatus.valueOf(rs.getString("status")),
        Priority.valueOf(rs.getString("priority")),
        Queue.valueOf(rs.getString("queue")),
        rs.getString("title"),
        rs.getString("summary"),
        rs.getString("trip_id"),
        rs.getString("order_id"),
        rs.getString("traveler_id"),
        rs.getString("disruption_id"),
        rs.getString("exposure_id"),
        rs.getString("component_id"),
        rs.getString("dedupe_key"),
        rs.getString("owner"),
        rs.getString("next_action"),
        rs.getString("next_action_role"),
        rs.getInt("escalation_level"),
        Rows.instantOrThrow(rs, "due_at"),
        Rows.instantOrThrow(rs, "opened_at"),
        Rows.instantOrThrow(rs, "updated_at"),
        Rows.instant(rs, "resolved_at"),
        Rows.instant(rs, "closed_at"),
        rs.getString("resolution"),
        rs.getString("source_event_id"),
        rs.getString("source_event_type"),
        rs.getLong("version"));
  }

  static List<AssistanceCase> none() {
    return new ArrayList<>();
  }
}
