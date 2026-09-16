package io.travelos.learning.store;

import io.travelos.common.tenant.TenantId;
import io.travelos.learning.model.EvidenceClass;
import io.travelos.learning.model.Outcome;
import io.travelos.learning.model.OutcomeKind;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Append-only. The evidence "as of" a cutoff is a query, never a mutable aggregate. */
@Repository
public class OutcomeRepository {
  private final JdbcClient jdbc;

  public OutcomeRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** True when the (key, revision) was new; false when the same logical revision already exists. */
  public boolean insert(Outcome o) {
    return jdbc.sql(
                """
                INSERT INTO outcome (outcome_id, tenant_id, logical_key, revision, kind, quality, supplier_key, provider,
                  evidence_class, trip_id, order_id, item_id, component_id, disruption_id, traveler_id, observed_at,
                  recorded_at, source, source_ref, provenance)
                VALUES (:id, :t, :key, :rev, :kind, :quality, :skey, :provider, :class, :trip, :order, :item, :component,
                  :disruption, :traveler, :observed, :recorded, :source, :ref, CAST(:prov AS jsonb))
                ON CONFLICT (tenant_id, logical_key, revision) DO NOTHING
                """)
            .param("id", o.outcomeId())
            .param("t", o.tenant().value())
            .param("key", o.logicalKey())
            .param("rev", o.revision())
            .param("kind", o.kind().name())
            .param("quality", o.quality().name())
            .param("skey", o.supplierKey())
            .param("provider", o.provider())
            .param("class", o.evidenceClass().name())
            .param("trip", o.tripId())
            .param("order", o.orderId())
            .param("item", o.itemId())
            .param("component", o.componentId())
            .param("disruption", o.disruptionId())
            .param("traveler", o.travelerId())
            .param("observed", Rows.ts(o.observedAt()))
            .param("recorded", Rows.ts(o.recordedAt()))
            .param("source", o.source())
            .param("ref", o.sourceRef())
            .param("prov", Rows.json(o.provenance()))
            .update()
        == 1;
  }

  public int currentRevision(TenantId tenant, String logicalKey) {
    return jdbc.sql(
            "SELECT COALESCE(MAX(revision), 0) FROM outcome WHERE tenant_id = :t AND logical_key = :k")
        .param("t", tenant.value())
        .param("k", logicalKey)
        .query(Integer.class)
        .single();
  }

  public Optional<Outcome> current(TenantId tenant, String logicalKey) {
    return jdbc.sql(
            "SELECT * FROM outcome WHERE tenant_id = :t AND logical_key = :k ORDER BY revision DESC LIMIT 1")
        .param("t", tenant.value())
        .param("k", logicalKey)
        .query(OutcomeRepository::map)
        .optional();
  }

  /** Every revision of every outcome of a trip, oldest first. */
  public List<Outcome> byTrip(TenantId tenant, String tripId) {
    return jdbc.sql(
            "SELECT * FROM outcome WHERE tenant_id = :t AND trip_id = :trip ORDER BY observed_at, logical_key, revision")
        .param("t", tenant.value())
        .param("trip", tripId)
        .query(OutcomeRepository::map)
        .list();
  }

  /**
   * The dataset a build at {@code cutoff} sees: for every logical key, the highest revision
   * recorded by the cutoff, when that revision was observed inside (windowStart, cutoff]. Rows are
   * never updated or deleted, so the same cutoff always yields the same rows.
   */
  public List<Outcome> asOf(
      TenantId tenant, EvidenceClass evidenceClass, Instant windowStart, Instant cutoff) {
    return jdbc.sql(
            """
            SELECT o.* FROM outcome o
            WHERE o.tenant_id = :t AND o.evidence_class = :c AND o.recorded_at <= :cutoff
              AND o.observed_at > :start AND o.observed_at <= :cutoff
              AND o.revision = (SELECT MAX(o2.revision) FROM outcome o2
                                WHERE o2.tenant_id = o.tenant_id AND o2.logical_key = o.logical_key
                                  AND o2.recorded_at <= :cutoff)
            ORDER BY o.logical_key, o.revision
            """)
        .param("t", tenant.value())
        .param("c", evidenceClass.name())
        .param("cutoff", Rows.ts(cutoff))
        .param("start", Rows.ts(windowStart))
        .query(OutcomeRepository::map)
        .list();
  }

  /**
   * Every revision recorded by the cutoff, oldest first: evaluation picks what was current when.
   */
  public List<Outcome> allRevisionsAsOf(
      TenantId tenant, EvidenceClass evidenceClass, Instant cutoff) {
    return jdbc.sql(
            """
            SELECT * FROM outcome WHERE tenant_id = :t AND evidence_class = :c AND recorded_at <= :cutoff
            ORDER BY recorded_at, logical_key, revision
            """)
        .param("t", tenant.value())
        .param("c", evidenceClass.name())
        .param("cutoff", Rows.ts(cutoff))
        .query(OutcomeRepository::map)
        .list();
  }

  /** Counts of current revisions by kind and evidence class (the operational summary). */
  public List<Map<String, Object>> summary(TenantId tenant) {
    return jdbc.sql(
            """
            SELECT o.kind, o.evidence_class, COUNT(*) AS n FROM outcome o
            WHERE o.tenant_id = :t
              AND o.revision = (SELECT MAX(o2.revision) FROM outcome o2 WHERE o2.tenant_id = o.tenant_id AND o2.logical_key = o.logical_key)
            GROUP BY o.kind, o.evidence_class ORDER BY o.kind, o.evidence_class
            """)
        .param("t", tenant.value())
        .query(
            (rs, i) ->
                Map.<String, Object>of(
                    "kind", rs.getString("kind"),
                    "evidenceClass", rs.getString("evidence_class"),
                    "count", rs.getLong("n")))
        .list();
  }

  static Outcome map(ResultSet rs, int i) throws SQLException {
    return new Outcome(
        rs.getString("outcome_id"),
        TenantId.of(rs.getString("tenant_id")),
        rs.getString("logical_key"),
        rs.getInt("revision"),
        OutcomeKind.valueOf(rs.getString("kind")),
        OutcomeKind.Quality.valueOf(rs.getString("quality")),
        rs.getString("supplier_key"),
        rs.getString("provider"),
        EvidenceClass.valueOf(rs.getString("evidence_class")),
        rs.getString("trip_id"),
        rs.getString("order_id"),
        rs.getString("item_id"),
        rs.getString("component_id"),
        rs.getString("disruption_id"),
        rs.getString("traveler_id"),
        Rows.instantOrThrow(rs, "observed_at"),
        Rows.instantOrThrow(rs, "recorded_at"),
        rs.getString("source"),
        rs.getString("source_ref"),
        Rows.map(rs, "provenance"));
  }
}
