package io.travelos.learning.store;

import io.travelos.common.tenant.TenantId;
import io.travelos.learning.model.Decision;
import io.travelos.learning.model.EvidenceClass;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class DecisionRepository {
  private final JdbcClient jdbc;

  public DecisionRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** True when new; a repeated optimization run id is a repeated representation. */
  public boolean insert(Decision d) {
    return jdbc.sql(
                """
                INSERT INTO decision (decision_id, tenant_id, trip_id, evidence_class, decided_at, selected_id, selected_keys,
                  candidates, learning, source_event_id)
                VALUES (:id, :t, :trip, :class, :at, :selected, CAST(:keys AS jsonb), CAST(:candidates AS jsonb),
                  CAST(:learning AS jsonb), :event)
                ON CONFLICT (decision_id) DO NOTHING
                """)
            .param("id", d.decisionId())
            .param("t", d.tenant().value())
            .param("trip", d.tripId())
            .param("class", d.evidenceClass().name())
            .param("at", Rows.ts(d.decidedAt()))
            .param("selected", d.selectedId())
            .param("keys", Rows.json(d.selectedKeys()))
            .param("candidates", Rows.json(d.candidates()))
            .param("learning", d.learning() == null ? null : Rows.json(d.learning()))
            .param("event", d.sourceEventId())
            .update()
        == 1;
  }

  /** Decisions decided by the cutoff, oldest first: the chronological record evaluation replays. */
  public List<Decision> asOf(
      TenantId tenant, EvidenceClass evidenceClass, Instant windowStart, Instant cutoff) {
    return jdbc.sql(
            """
            SELECT * FROM decision WHERE tenant_id = :t AND evidence_class = :c
              AND decided_at > :start AND decided_at <= :cutoff
            ORDER BY decided_at, decision_id
            """)
        .param("t", tenant.value())
        .param("c", evidenceClass.name())
        .param("start", Rows.ts(windowStart))
        .param("cutoff", Rows.ts(cutoff))
        .query(DecisionRepository::map)
        .list();
  }

  public long count(TenantId tenant) {
    return jdbc.sql("SELECT COUNT(*) FROM decision WHERE tenant_id = :t")
        .param("t", tenant.value())
        .query(Long.class)
        .single();
  }

  static Decision map(ResultSet rs, int i) throws SQLException {
    return new Decision(
        rs.getString("decision_id"),
        TenantId.of(rs.getString("tenant_id")),
        rs.getString("trip_id"),
        EvidenceClass.valueOf(rs.getString("evidence_class")),
        Rows.instantOrThrow(rs, "decided_at"),
        rs.getString("selected_id"),
        Rows.strings(rs, "selected_keys"),
        Rows.maps(rs, "candidates"),
        Rows.mapOrNull(rs, "learning"),
        rs.getString("source_event_id"));
  }
}
