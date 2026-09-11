package io.travelos.policy.store;

import io.travelos.common.identity.Principal;
import io.travelos.common.tenant.TenantId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Append-only evidence. There is deliberately no update or delete here. */
@Repository
public class DecisionRepository {

  /**
   * @param decisionJson the full PolicyDecision message as protobuf JSON: what the caller received
   */
  public record Record(
      String decisionId,
      TenantId tenant,
      String evaluationId,
      String tripId,
      String travelerId,
      @Nullable String bundleId,
      @Nullable String action,
      String policyId,
      int policyVersion,
      String outcome,
      boolean requiresApproval,
      Principal evaluatedFor,
      String decisionJson,
      Instant evaluatedAt) {}

  private static final String COLUMNS =
      "decision_id, tenant_id, evaluation_id, trip_id, traveler_id, bundle_id, action, policy_id,"
          + " policy_version, outcome, requires_approval, evaluated_for, decision::text AS decision,"
          + " evaluated_at";

  private final JdbcClient jdbc;

  public DecisionRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public void insert(Record record) {
    jdbc.sql(
            """
            INSERT INTO policy_decision (decision_id, tenant_id, evaluation_id, trip_id, traveler_id,
              bundle_id, action, policy_id, policy_version, outcome, requires_approval, evaluated_for,
              decision, evaluated_at)
            VALUES (:decisionId, :tenantId, :evaluationId, :tripId, :travelerId, :bundleId, :action,
              :policyId, :policyVersion, :outcome, :requiresApproval, :evaluatedFor,
              CAST(:decision AS jsonb), :evaluatedAt)
            """)
        .param("decisionId", record.decisionId())
        .param("tenantId", record.tenant().value())
        .param("evaluationId", record.evaluationId())
        .param("tripId", record.tripId())
        .param("travelerId", record.travelerId())
        .param("bundleId", record.bundleId())
        .param("action", record.action())
        .param("policyId", record.policyId())
        .param("policyVersion", record.policyVersion())
        .param("outcome", record.outcome())
        .param("requiresApproval", record.requiresApproval())
        .param("evaluatedFor", record.evaluatedFor().id())
        .param("decision", record.decisionJson())
        .param("evaluatedAt", OffsetDateTime.ofInstant(record.evaluatedAt(), ZoneOffset.UTC))
        .update();
  }

  public Optional<Record> find(TenantId tenant, String decisionId) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM policy_decision WHERE tenant_id = :tenantId AND decision_id = :id")
        .param("tenantId", tenant.value())
        .param("id", decisionId)
        .query(DecisionRepository::map)
        .optional();
  }

  public List<Record> byTrip(TenantId tenant, String tripId) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM policy_decision WHERE tenant_id = :tenantId AND trip_id = :tripId"
                + " ORDER BY evaluated_at, decision_id")
        .param("tenantId", tenant.value())
        .param("tripId", tripId)
        .query(DecisionRepository::map)
        .list();
  }

  private static Record map(ResultSet rs, int rowNum) throws SQLException {
    return new Record(
        rs.getString("decision_id"),
        TenantId.of(rs.getString("tenant_id")),
        rs.getString("evaluation_id"),
        rs.getString("trip_id"),
        rs.getString("traveler_id"),
        rs.getString("bundle_id"),
        rs.getString("action"),
        rs.getString("policy_id"),
        rs.getInt("policy_version"),
        rs.getString("outcome"),
        rs.getBoolean("requires_approval"),
        Principal.parse(rs.getString("evaluated_for")),
        rs.getString("decision"),
        rs.getObject("evaluated_at", OffsetDateTime.class).toInstant());
  }
}
