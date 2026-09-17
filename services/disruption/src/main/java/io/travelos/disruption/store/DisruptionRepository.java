package io.travelos.disruption.store;

import io.travelos.common.money.Money;
import io.travelos.common.tenant.TenantId;
import io.travelos.disruption.model.Disruption;
import io.travelos.disruption.model.DisruptionStatus;
import io.travelos.disruption.model.RecoveryApproval;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class DisruptionRepository {

  private final JdbcClient jdbc;

  public DisruptionRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  // ------------------------------------------------------------------ dedupe

  /** True when this event id was not seen before (and is now recorded). */
  public boolean markProcessed(String eventId, String eventType, Instant now) {
    return jdbc.sql(
                """
                INSERT INTO processed_event (event_id, event_type, processed_at)
                VALUES (:id, :type, :now) ON CONFLICT (event_id) DO NOTHING
                """)
            .param("id", eventId)
            .param("type", eventType)
            .param("now", Timestamp.from(now))
            .update()
        == 1;
  }

  // ------------------------------------------------------------------ disruptions

  public void insert(Disruption d) {
    jdbc.sql(
            """
            INSERT INTO disruption (disruption_id, tenant_id, trip_id, order_id, traveler_id, segment_id, type, supplier,
              supplier_event_id, external_order_id, record_locator, detected_at, status, severity, raw_reference, reason,
              affected, recovery, failure_stage, failure_code, source_event_id, version, created_at, updated_at)
            VALUES (:id, :tenant, :trip, :order, :traveler, :segment, :type, :supplier, :supplierEvent, :external,
              :locator, :detectedAt, :status, :severity, :rawRef, :reason, CAST(:affected AS jsonb),
              CAST(:recovery AS jsonb), :failureStage, :failureCode, :sourceEvent, 0, :createdAt, :updatedAt)
            """)
        .param("id", d.disruptionId())
        .param("tenant", d.tenant().value())
        .param("trip", d.tripId())
        .param("order", d.orderId())
        .param("traveler", d.travelerId())
        .param("segment", d.segmentId())
        .param("type", d.type())
        .param("supplier", d.supplier())
        .param("supplierEvent", d.supplierEventId())
        .param("external", d.externalOrderId())
        .param("locator", d.recordLocator())
        .param("detectedAt", Timestamp.from(d.detectedAt()))
        .param("status", d.status().name())
        .param("severity", d.severity())
        .param("rawRef", d.rawReference())
        .param("reason", d.reason())
        .param("affected", d.affectedJson())
        .param("recovery", d.recoveryJson())
        .param("failureStage", d.failureStage())
        .param("failureCode", d.failureCode())
        .param("sourceEvent", d.sourceEventId())
        .param("createdAt", Timestamp.from(d.createdAt()))
        .param("updatedAt", Timestamp.from(d.updatedAt()))
        .update();
    history(d.disruptionId(), d.tenant(), null, d.status(), "detected", d.createdAt());
  }

  public Optional<Disruption> find(TenantId tenant, String disruptionId) {
    return jdbc.sql("SELECT * FROM disruption WHERE tenant_id = :t AND disruption_id = :id")
        .param("t", tenant.value())
        .param("id", disruptionId)
        .query(DisruptionRepository::map)
        .optional();
  }

  public Optional<Disruption> findBySupplierEvent(
      TenantId tenant, String supplier, String supplierEventId) {
    return jdbc.sql(
            "SELECT * FROM disruption WHERE tenant_id = :t AND supplier = :s AND supplier_event_id = :e")
        .param("t", tenant.value())
        .param("s", supplier)
        .param("e", supplierEventId)
        .query(DisruptionRepository::map)
        .optional();
  }

  public List<Disruption> byStatus(DisruptionStatus status, int limit) {
    return jdbc.sql(
            "SELECT * FROM disruption WHERE status = :s ORDER BY detected_at, disruption_id LIMIT :n")
        .param("s", status.name())
        .param("n", limit)
        .query(DisruptionRepository::map)
        .list();
  }

  /** The tenant's disruptions, newest first, optionally at one status (an operations inbox). */
  public List<Disruption> byTenant(TenantId tenant, @Nullable DisruptionStatus status, int limit) {
    String where = status == null ? "" : " AND status = :s";
    var spec =
        jdbc.sql(
                "SELECT * FROM disruption WHERE tenant_id = :t"
                    + where
                    + " ORDER BY detected_at DESC, disruption_id LIMIT :n")
            .param("t", tenant.value())
            .param("n", limit);
    if (status != null) {
      spec = spec.param("s", status.name());
    }
    return spec.query(DisruptionRepository::map).list();
  }

  public List<Disruption> byTrip(TenantId tenant, String tripId) {
    return jdbc.sql(
            "SELECT * FROM disruption WHERE tenant_id = :t AND trip_id = :trip ORDER BY detected_at DESC, disruption_id")
        .param("t", tenant.value())
        .param("trip", tripId)
        .query(DisruptionRepository::map)
        .list();
  }

  /**
   * Optimistic transition: the row must still be at {@code from.version()}. Impact fields and the
   * recovery state are updated in the same statement when given.
   */
  public boolean transition(
      Disruption from,
      DisruptionStatus to,
      @Nullable String reason,
      @Nullable Impact impact,
      @Nullable String recoveryJson,
      @Nullable String failureStage,
      @Nullable String failureCode,
      Instant now) {
    int rows =
        jdbc.sql(
                """
                UPDATE disruption SET status = :to,
                  trip_id = coalesce(:trip, trip_id), order_id = coalesce(:order, order_id),
                  traveler_id = coalesce(:traveler, traveler_id), segment_id = coalesce(:segment, segment_id),
                  recovery = CAST(coalesce(:recovery, recovery::text) AS jsonb),
                  failure_stage = :failureStage, failure_code = :failureCode,
                  version = version + 1, updated_at = :now
                WHERE tenant_id = :t AND disruption_id = :id AND version = :version
                """)
            .param("to", to.name())
            .param("trip", impact == null ? null : impact.tripId())
            .param("order", impact == null ? null : impact.orderId())
            .param("traveler", impact == null ? null : impact.travelerId())
            .param("segment", impact == null ? null : impact.segmentId())
            .param("recovery", recoveryJson)
            .param("failureStage", failureStage)
            .param("failureCode", failureCode)
            .param("now", Timestamp.from(now))
            .param("t", from.tenant().value())
            .param("id", from.disruptionId())
            .param("version", from.version())
            .update();
    if (rows == 1) {
      history(from.disruptionId(), from.tenant(), from.status(), to, reason, now);
    }
    return rows == 1;
  }

  public record Impact(
      String tripId, String orderId, String travelerId, @Nullable String segmentId) {}

  private void history(
      String disruptionId,
      TenantId tenant,
      @Nullable DisruptionStatus from,
      DisruptionStatus to,
      @Nullable String reason,
      Instant now) {
    jdbc.sql(
            """
            INSERT INTO disruption_status_history (disruption_id, tenant_id, from_status, to_status, reason, occurred_at)
            VALUES (:id, :t, :from, :to, :reason, :now)
            """)
        .param("id", disruptionId)
        .param("t", tenant.value())
        .param("from", from == null ? null : from.name())
        .param("to", to.name())
        .param(
            "reason",
            reason == null ? null : reason.length() > 500 ? reason.substring(0, 500) : reason)
        .param("now", Timestamp.from(now))
        .update();
  }

  public List<String[]> history(TenantId tenant, String disruptionId) {
    return jdbc.sql(
            "SELECT from_status, to_status, reason, occurred_at FROM disruption_status_history WHERE tenant_id = :t AND disruption_id = :id ORDER BY id")
        .param("t", tenant.value())
        .param("id", disruptionId)
        .query(
            (rs, i) ->
                new String[] {
                  rs.getString("from_status"),
                  rs.getString("to_status"),
                  rs.getString("reason"),
                  rs.getTimestamp("occurred_at").toInstant().toString()
                })
        .list();
  }

  // ------------------------------------------------------------------ immutable records

  /** Returns false when a decision already exists (the database refuses a second one). */
  public boolean insertDecision(String decisionId, Disruption d, String recordJson, Instant now) {
    return jdbc.sql(
                """
                INSERT INTO recovery_decision (decision_id, disruption_id, tenant_id, record, created_at)
                VALUES (:id, :d, :t, CAST(:record AS jsonb), :now) ON CONFLICT (disruption_id) DO NOTHING
                """)
            .param("id", decisionId)
            .param("d", d.disruptionId())
            .param("t", d.tenant().value())
            .param("record", recordJson)
            .param("now", Timestamp.from(now))
            .update()
        == 1;
  }

  public Optional<String> decision(TenantId tenant, String disruptionId) {
    return jdbc.sql(
            "SELECT record::text FROM recovery_decision WHERE tenant_id = :t AND disruption_id = :id")
        .param("t", tenant.value())
        .param("id", disruptionId)
        .query(String.class)
        .optional();
  }

  public boolean insertOutcome(
      String outcomeId, Disruption d, DisruptionStatus status, String recordJson, Instant now) {
    return jdbc.sql(
                """
                INSERT INTO recovery_outcome (outcome_id, disruption_id, tenant_id, status, record, created_at)
                VALUES (:id, :d, :t, :status, CAST(:record AS jsonb), :now) ON CONFLICT (disruption_id) DO NOTHING
                """)
            .param("id", outcomeId)
            .param("d", d.disruptionId())
            .param("t", d.tenant().value())
            .param("status", status.name())
            .param("record", recordJson)
            .param("now", Timestamp.from(now))
            .update()
        == 1;
  }

  public Optional<String> outcome(TenantId tenant, String disruptionId) {
    return jdbc.sql(
            "SELECT record::text FROM recovery_outcome WHERE tenant_id = :t AND disruption_id = :id")
        .param("t", tenant.value())
        .param("id", disruptionId)
        .query(String.class)
        .optional();
  }

  // ------------------------------------------------------------------ approvals

  public void insertApproval(RecoveryApproval a) {
    jdbc.sql(
            """
            INSERT INTO recovery_approval (approval_id, disruption_id, tenant_id, trip_id, traveler_id, required_role, status,
              policy_decision_id, incremental_currency, incremental_minor, requested_at)
            VALUES (:id, :d, :t, :trip, :traveler, :role, :status, :pd, :currency, :minor, :at)
            """)
        .param("id", a.approvalId())
        .param("d", a.disruptionId())
        .param("t", a.tenant().value())
        .param("trip", a.tripId())
        .param("traveler", a.travelerId())
        .param("role", a.requiredRole())
        .param("status", a.status().name())
        .param("pd", a.policyDecisionId())
        .param("currency", a.incrementalCost() == null ? null : a.incrementalCost().currency())
        .param("minor", a.incrementalCost() == null ? null : a.incrementalCost().amountMinor())
        .param("at", Timestamp.from(a.requestedAt()))
        .update();
  }

  public Optional<RecoveryApproval> findApproval(TenantId tenant, String approvalId) {
    return jdbc.sql("SELECT * FROM recovery_approval WHERE tenant_id = :t AND approval_id = :id")
        .param("t", tenant.value())
        .param("id", approvalId)
        .query(DisruptionRepository::mapApproval)
        .optional();
  }

  public Optional<RecoveryApproval> pendingApproval(TenantId tenant, String disruptionId) {
    return jdbc.sql(
            "SELECT * FROM recovery_approval WHERE tenant_id = :t AND disruption_id = :d AND status = 'PENDING' ORDER BY requested_at DESC LIMIT 1")
        .param("t", tenant.value())
        .param("d", disruptionId)
        .query(DisruptionRepository::mapApproval)
        .optional();
  }

  public Optional<RecoveryApproval> latestApproval(TenantId tenant, String disruptionId) {
    return jdbc.sql(
            "SELECT * FROM recovery_approval WHERE tenant_id = :t AND disruption_id = :d ORDER BY requested_at DESC LIMIT 1")
        .param("t", tenant.value())
        .param("d", disruptionId)
        .query(DisruptionRepository::mapApproval)
        .optional();
  }

  public boolean decide(
      RecoveryApproval pending,
      RecoveryApproval.Status decision,
      String decidedBy,
      @Nullable String comment,
      String idempotencyKey,
      Instant now) {
    return jdbc.sql(
                """
                UPDATE recovery_approval SET status = :status, decided_by = :by, decided_at = :at, comment = :comment,
                  decision_idempotency_key = :key
                WHERE approval_id = :id AND status = 'PENDING'
                """)
            .param("status", decision.name())
            .param("by", decidedBy)
            .param("at", Timestamp.from(now))
            .param("comment", comment)
            .param("key", idempotencyKey)
            .param("id", pending.approvalId())
            .update()
        == 1;
  }

  // ------------------------------------------------------------------ mapping

  private static Disruption map(ResultSet rs, int i) throws SQLException {
    return new Disruption(
        rs.getString("disruption_id"),
        TenantId.of(rs.getString("tenant_id")),
        rs.getString("trip_id"),
        rs.getString("order_id"),
        rs.getString("traveler_id"),
        rs.getString("segment_id"),
        rs.getString("type"),
        rs.getString("supplier"),
        rs.getString("supplier_event_id"),
        rs.getString("external_order_id"),
        rs.getString("record_locator"),
        rs.getTimestamp("detected_at").toInstant(),
        DisruptionStatus.valueOf(rs.getString("status")),
        rs.getString("severity"),
        rs.getString("raw_reference"),
        rs.getString("reason"),
        rs.getString("affected"),
        rs.getString("recovery"),
        rs.getString("failure_stage"),
        rs.getString("failure_code"),
        rs.getString("source_event_id"),
        rs.getLong("version"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant());
  }

  private static RecoveryApproval mapApproval(ResultSet rs, int i) throws SQLException {
    Timestamp decidedAt = rs.getTimestamp("decided_at");
    String currency = rs.getString("incremental_currency");
    Long minor = rs.getObject("incremental_minor", Long.class);
    return new RecoveryApproval(
        rs.getString("approval_id"),
        rs.getString("disruption_id"),
        TenantId.of(rs.getString("tenant_id")),
        rs.getString("trip_id"),
        rs.getString("traveler_id"),
        rs.getString("required_role"),
        RecoveryApproval.Status.valueOf(rs.getString("status")),
        rs.getString("policy_decision_id"),
        currency == null || minor == null ? null : Money.of(currency, minor),
        rs.getTimestamp("requested_at").toInstant(),
        rs.getString("decided_by"),
        decidedAt == null ? null : decidedAt.toInstant(),
        rs.getString("comment"),
        rs.getString("decision_idempotency_key"));
  }
}
