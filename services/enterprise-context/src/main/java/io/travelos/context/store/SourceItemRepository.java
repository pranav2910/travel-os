package io.travelos.context.store;

import io.travelos.common.tenant.TenantId;
import io.travelos.context.model.ConnectorKind;
import io.travelos.context.model.SourceItem;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class SourceItemRepository {
  private final JdbcClient jdbc;

  public SourceItemRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<SourceItem> find(TenantId tenant, String connectorId, String sourceId) {
    return jdbc.sql(
            "SELECT * FROM source_item WHERE tenant_id = :t AND connector_id = :c AND source_id = :s")
        .param("t", tenant.value())
        .param("c", connectorId)
        .param("s", sourceId)
        .query(SourceItemRepository::map)
        .optional();
  }

  public void upsert(SourceItem item) {
    jdbc.sql(
            """
            INSERT INTO source_item (tenant_id, connector_id, source_id, kind, revision, status, normalized, candidate_id,
              first_seen_at, observed_at)
            VALUES (:t, :c, :s, :kind, :rev, :status, CAST(:json AS jsonb), :candidate, :first, :observed)
            ON CONFLICT (tenant_id, connector_id, source_id) DO UPDATE SET
              revision = EXCLUDED.revision, status = EXCLUDED.status, normalized = EXCLUDED.normalized,
              candidate_id = COALESCE(EXCLUDED.candidate_id, source_item.candidate_id), observed_at = EXCLUDED.observed_at
            """)
        .param("t", item.tenant().value())
        .param("c", item.connectorId())
        .param("s", item.sourceId())
        .param("kind", item.kind().name())
        .param("rev", item.revision())
        .param("status", item.status().name())
        .param("json", item.normalizedJson())
        .param("candidate", item.candidateId())
        .param("first", Rows.ts(item.firstSeenAt()))
        .param("observed", Rows.ts(item.observedAt()))
        .update();
  }

  /** Evidence history: one row per revision ever seen, never rewritten. */
  public void recordRevision(SourceItem item, @Nullable String runId) {
    jdbc.sql(
            """
            INSERT INTO source_item_revision (tenant_id, connector_id, source_id, revision, status, normalized, run_id, observed_at)
            VALUES (:t, :c, :s, :rev, :status, CAST(:json AS jsonb), :run, :observed)
            ON CONFLICT (tenant_id, connector_id, source_id, revision) DO NOTHING
            """)
        .param("t", item.tenant().value())
        .param("c", item.connectorId())
        .param("s", item.sourceId())
        .param("rev", item.revision())
        .param("status", item.status().name())
        .param("json", item.normalizedJson())
        .param("run", runId)
        .param("observed", Rows.ts(item.observedAt()))
        .update();
  }

  public void link(TenantId tenant, String connectorId, String sourceId, String candidateId) {
    jdbc.sql(
            """
            UPDATE source_item SET candidate_id = :cand
            WHERE tenant_id = :t AND connector_id = :c AND source_id = :s
            """)
        .param("cand", candidateId)
        .param("t", tenant.value())
        .param("c", connectorId)
        .param("s", sourceId)
        .update();
  }

  public List<SourceItem> byCandidate(TenantId tenant, String candidateId) {
    return jdbc.sql(
            "SELECT * FROM source_item WHERE tenant_id = :t AND candidate_id = :c ORDER BY first_seen_at")
        .param("t", tenant.value())
        .param("c", candidateId)
        .query(SourceItemRepository::map)
        .list();
  }

  public List<SourceItem> byKind(TenantId tenant, ConnectorKind kind) {
    return jdbc.sql(
            "SELECT * FROM source_item WHERE tenant_id = :t AND kind = :k ORDER BY first_seen_at")
        .param("t", tenant.value())
        .param("k", kind.name())
        .query(SourceItemRepository::map)
        .list();
  }

  public record Revision(
      long revision,
      String status,
      String normalizedJson,
      @Nullable String runId,
      Instant observedAt) {}

  public List<Revision> revisions(TenantId tenant, String connectorId, String sourceId) {
    return jdbc.sql(
            """
            SELECT revision, status, normalized, run_id, observed_at FROM source_item_revision
            WHERE tenant_id = :t AND connector_id = :c AND source_id = :s ORDER BY revision
            """)
        .param("t", tenant.value())
        .param("c", connectorId)
        .param("s", sourceId)
        .query(
            (rs, i) ->
                new Revision(
                    rs.getLong("revision"),
                    rs.getString("status"),
                    rs.getString("normalized"),
                    rs.getString("run_id"),
                    Rows.instant(rs, "observed_at")))
        .list();
  }

  static SourceItem map(ResultSet rs, int i) throws SQLException {
    return new SourceItem(
        TenantId.of(rs.getString("tenant_id")),
        rs.getString("connector_id"),
        rs.getString("source_id"),
        ConnectorKind.valueOf(rs.getString("kind")),
        rs.getLong("revision"),
        SourceItem.Status.valueOf(rs.getString("status")),
        rs.getString("normalized"),
        rs.getString("candidate_id"),
        Rows.instant(rs, "first_seen_at"),
        Rows.instant(rs, "observed_at"));
  }
}
