package io.travelos.context.store;

import io.travelos.common.tenant.TenantId;
import io.travelos.context.model.Connector;
import io.travelos.context.model.ConnectorKind;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ConnectorRepository {
  private final JdbcClient jdbc;

  public ConnectorRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public void insert(Connector c) {
    jdbc.sql(
            """
            INSERT INTO connector (connector_id, tenant_id, kind, provider, status, config, checkpoint, next_sync_at,
              version, created_by, created_at, updated_at)
            VALUES (:id, :tenant, :kind, :provider, :status, CAST(:config AS jsonb), :checkpoint, :next, 0, :by, :now, :now)
            """)
        .param("id", c.connectorId())
        .param("tenant", c.tenant().value())
        .param("kind", c.kind().name())
        .param("provider", c.provider())
        .param("status", c.status().name())
        .param("config", c.configJson())
        .param("checkpoint", c.checkpoint())
        .param("next", Rows.ts(c.nextSyncAt()))
        .param("by", c.createdBy())
        .param("now", Rows.ts(c.createdAt()))
        .update();
  }

  public Optional<Connector> find(TenantId tenant, String connectorId) {
    return jdbc.sql("SELECT * FROM connector WHERE tenant_id = :t AND connector_id = :id")
        .param("t", tenant.value())
        .param("id", connectorId)
        .query(ConnectorRepository::map)
        .optional();
  }

  /** Row lock for the duration of a page: two workers never advance one checkpoint at once. */
  public Optional<Connector> lock(TenantId tenant, String connectorId) {
    return jdbc.sql(
            "SELECT * FROM connector WHERE tenant_id = :t AND connector_id = :id FOR UPDATE")
        .param("t", tenant.value())
        .param("id", connectorId)
        .query(ConnectorRepository::map)
        .optional();
  }

  public Optional<Connector> findByKind(TenantId tenant, ConnectorKind kind, String provider) {
    return jdbc.sql("SELECT * FROM connector WHERE tenant_id = :t AND kind = :k AND provider = :p")
        .param("t", tenant.value())
        .param("k", kind.name())
        .param("p", provider)
        .query(ConnectorRepository::map)
        .optional();
  }

  public List<Connector> list(TenantId tenant) {
    return jdbc.sql("SELECT * FROM connector WHERE tenant_id = :t ORDER BY kind, provider")
        .param("t", tenant.value())
        .query(ConnectorRepository::map)
        .list();
  }

  /** The connectors whose scheduled time has come; each one is claimed atomically by one caller. */
  public List<Connector> dueForSync(Instant now, Instant next) {
    return jdbc.sql(
            """
            UPDATE connector SET next_sync_at = :next, updated_at = :now
            WHERE status = 'ENABLED' AND running_run_id IS NULL AND (next_sync_at IS NULL OR next_sync_at <= :now)
              AND COALESCE(config->>'scheduled', 'true') <> 'false'
            RETURNING *
            """)
        .param("now", Rows.ts(now))
        .param("next", Rows.ts(next))
        .query(ConnectorRepository::map)
        .list();
  }

  public void setStatus(TenantId tenant, String connectorId, Connector.Status status, Instant now) {
    jdbc.sql(
            """
            UPDATE connector SET status = :s, version = version + 1, updated_at = :now
            WHERE tenant_id = :t AND connector_id = :id
            """)
        .param("s", status.name())
        .param("now", Rows.ts(now))
        .param("t", tenant.value())
        .param("id", connectorId)
        .update();
  }

  public void updateConfig(TenantId tenant, String connectorId, String configJson, Instant now) {
    jdbc.sql(
            """
            UPDATE connector SET config = CAST(:c AS jsonb), next_sync_at = NULL, version = version + 1, updated_at = :now
            WHERE tenant_id = :t AND connector_id = :id
            """)
        .param("c", configJson)
        .param("now", Rows.ts(now))
        .param("t", tenant.value())
        .param("id", connectorId)
        .update();
  }

  public boolean claimRun(TenantId tenant, String connectorId, String runId, Instant now) {
    return jdbc.sql(
                """
                UPDATE connector SET running_run_id = :run, last_run_id = :run, last_sync_at = :now, version = version + 1,
                  updated_at = :now
                WHERE tenant_id = :t AND connector_id = :id AND (running_run_id IS NULL OR running_run_id = :run)
                """)
            .param("run", runId)
            .param("now", Rows.ts(now))
            .param("t", tenant.value())
            .param("id", connectorId)
            .update()
        == 1;
  }

  /** The durable write boundary: the checkpoint moves with the page that justifies it. */
  public void advanceCheckpoint(
      TenantId tenant, String connectorId, String checkpoint, Instant now) {
    jdbc.sql(
            """
            UPDATE connector SET checkpoint = :cp, version = version + 1, updated_at = :now
            WHERE tenant_id = :t AND connector_id = :id
            """)
        .param("cp", checkpoint)
        .param("now", Rows.ts(now))
        .param("t", tenant.value())
        .param("id", connectorId)
        .update();
  }

  public void finishRun(
      TenantId tenant,
      String connectorId,
      String runId,
      boolean success,
      @Nullable String errorCode,
      @Nullable String errorMessage,
      Instant now) {
    jdbc.sql(
            """
            UPDATE connector SET running_run_id = CASE WHEN running_run_id = :run THEN NULL ELSE running_run_id END,
              last_success_at = CASE WHEN :ok THEN :now ELSE last_success_at END,
              last_error_code = :code, last_error_message = :message, version = version + 1, updated_at = :now
            WHERE tenant_id = :t AND connector_id = :id
            """)
        .param("run", runId)
        .param("ok", success)
        .param("now", Rows.ts(now))
        .param("code", errorCode)
        .param("message", errorMessage)
        .param("t", tenant.value())
        .param("id", connectorId)
        .update();
  }

  static Connector map(ResultSet rs, int i) throws SQLException {
    return new Connector(
        rs.getString("connector_id"),
        TenantId.of(rs.getString("tenant_id")),
        ConnectorKind.valueOf(rs.getString("kind")),
        rs.getString("provider"),
        Connector.Status.valueOf(rs.getString("status")),
        rs.getString("config"),
        rs.getString("checkpoint"),
        rs.getString("running_run_id"),
        Rows.instant(rs, "next_sync_at"),
        rs.getString("last_run_id"),
        Rows.instant(rs, "last_sync_at"),
        Rows.instant(rs, "last_success_at"),
        rs.getString("last_error_code"),
        rs.getString("last_error_message"),
        rs.getLong("version"),
        rs.getString("created_by"),
        Rows.instant(rs, "created_at"),
        Rows.instant(rs, "updated_at"));
  }
}
