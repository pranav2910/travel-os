package io.travelos.context.store;

import io.travelos.common.tenant.TenantId;
import io.travelos.context.model.SyncRun;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class SyncRunRepository {
  private final JdbcClient jdbc;

  public SyncRunRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public void insert(SyncRun r) {
    jdbc.sql(
            """
            INSERT INTO sync_run (run_id, connector_id, tenant_id, trigger, status, since, watermark, requested_by,
              notification_id, started_at)
            VALUES (:id, :connector, :tenant, :trigger, :status, :since, :watermark, :by, :notification, :started)
            ON CONFLICT (run_id) DO NOTHING
            """)
        .param("id", r.runId())
        .param("connector", r.connectorId())
        .param("tenant", r.tenant().value())
        .param("trigger", r.trigger().name())
        .param("status", r.status().name())
        .param("since", r.since())
        .param("watermark", r.watermark())
        .param("by", r.requestedBy())
        .param("notification", r.notificationId())
        .param("started", Rows.ts(r.startedAt()))
        .update();
  }

  public Optional<SyncRun> find(TenantId tenant, String runId) {
    return jdbc.sql("SELECT * FROM sync_run WHERE tenant_id = :t AND run_id = :id")
        .param("t", tenant.value())
        .param("id", runId)
        .query(SyncRunRepository::map)
        .optional();
  }

  public List<SyncRun> byConnector(TenantId tenant, String connectorId, int limit) {
    return jdbc.sql(
            """
            SELECT * FROM sync_run WHERE tenant_id = :t AND connector_id = :c
            ORDER BY started_at DESC LIMIT :n
            """)
        .param("t", tenant.value())
        .param("c", connectorId)
        .param("n", limit)
        .query(SyncRunRepository::map)
        .list();
  }

  public void page(
      String runId,
      String lastCursor,
      String watermark,
      int itemsSeen,
      int itemsChanged,
      int candidatesTouched) {
    jdbc.sql(
            """
            UPDATE sync_run SET status = 'RUNNING', pages = pages + 1, last_cursor = :cursor, watermark = :wm,
              items_seen = items_seen + :seen, items_changed = items_changed + :changed,
              candidates_touched = candidates_touched + :touched
            WHERE run_id = :id
            """)
        .param("cursor", lastCursor)
        .param("wm", watermark)
        .param("seen", itemsSeen)
        .param("changed", itemsChanged)
        .param("touched", candidatesTouched)
        .param("id", runId)
        .update();
  }

  public void finish(
      String runId,
      SyncRun.Status status,
      @Nullable String failureCode,
      @Nullable String failureMessage,
      Instant now) {
    jdbc.sql(
            """
            UPDATE sync_run SET status = :s, failure_code = :code, failure_message = :message, finished_at = :now
            WHERE run_id = :id AND status IN ('REQUESTED', 'RUNNING')
            """)
        .param("s", status.name())
        .param("code", failureCode)
        .param("message", failureMessage)
        .param("now", Rows.ts(now))
        .param("id", runId)
        .update();
  }

  static SyncRun map(ResultSet rs, int i) throws SQLException {
    return new SyncRun(
        rs.getString("run_id"),
        rs.getString("connector_id"),
        TenantId.of(rs.getString("tenant_id")),
        SyncRun.Trigger.valueOf(rs.getString("trigger")),
        SyncRun.Status.valueOf(rs.getString("status")),
        rs.getString("since"),
        rs.getString("watermark"),
        rs.getInt("pages"),
        rs.getInt("items_seen"),
        rs.getInt("items_changed"),
        rs.getInt("candidates_touched"),
        rs.getString("last_cursor"),
        rs.getString("failure_code"),
        rs.getString("failure_message"),
        rs.getString("requested_by"),
        rs.getString("notification_id"),
        Rows.instant(rs, "started_at"),
        Rows.instant(rs, "finished_at"));
  }
}
