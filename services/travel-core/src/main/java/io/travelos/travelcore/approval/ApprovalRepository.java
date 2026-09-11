package io.travelos.travelcore.approval;

import io.travelos.common.tenant.TenantId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ApprovalRepository {

  private static final String COLUMNS =
      "approval_id, tenant_id, trip_id, required_role, status, policy_decision_id, requested_at,"
          + " decided_by, decided_at, comment, decision_idempotency_key";

  private final JdbcClient jdbc;

  public ApprovalRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public void insert(Approval a) {
    jdbc.sql(
            """
            INSERT INTO approval (approval_id, tenant_id, trip_id, required_role, status,
              policy_decision_id, requested_at)
            VALUES (:id, :tenant, :trip, :role, :status, :policyDecisionId, :requestedAt)
            """)
        .param("id", a.approvalId())
        .param("tenant", a.tenant().value())
        .param("trip", a.tripId())
        .param("role", a.requiredRole())
        .param("status", a.status().name())
        .param("policyDecisionId", a.policyDecisionId())
        .param("requestedAt", ts(a.requestedAt()))
        .update();
  }

  public Optional<Approval> find(TenantId tenant, String approvalId) {
    return jdbc.sql(
            "SELECT " + COLUMNS + " FROM approval WHERE tenant_id = :t AND approval_id = :id")
        .param("t", tenant.value())
        .param("id", approvalId)
        .query(ApprovalRepository::map)
        .optional();
  }

  public Optional<Approval> pendingForTrip(TenantId tenant, String tripId) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM approval WHERE tenant_id = :t AND trip_id = :trip AND status = 'PENDING'")
        .param("t", tenant.value())
        .param("trip", tripId)
        .query(ApprovalRepository::map)
        .optional();
  }

  public Optional<Approval> latestForTrip(TenantId tenant, String tripId) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM approval WHERE tenant_id = :t AND trip_id = :trip"
                + " ORDER BY requested_at DESC LIMIT 1")
        .param("t", tenant.value())
        .param("trip", tripId)
        .query(ApprovalRepository::map)
        .optional();
  }

  /** Records the decision only if the approval is still pending. */
  public boolean decide(
      Approval pending,
      Approval.Status decision,
      String decidedBy,
      @Nullable String comment,
      String idempotencyKey,
      Instant now) {
    return jdbc.sql(
                """
                UPDATE approval SET status = :status, decided_by = :by, decided_at = :at, comment = :comment,
                  decision_idempotency_key = :key
                WHERE tenant_id = :t AND approval_id = :id AND status = 'PENDING'
                """)
            .param("status", decision.name())
            .param("by", decidedBy)
            .param("at", ts(now))
            .param("comment", comment)
            .param("key", idempotencyKey)
            .param("t", pending.tenant().value())
            .param("id", pending.approvalId())
            .update()
        == 1;
  }

  private static Approval map(ResultSet rs, int rowNum) throws SQLException {
    OffsetDateTime decidedAt = rs.getObject("decided_at", OffsetDateTime.class);
    return new Approval(
        rs.getString("approval_id"),
        TenantId.of(rs.getString("tenant_id")),
        rs.getString("trip_id"),
        rs.getString("required_role"),
        Approval.Status.valueOf(rs.getString("status")),
        rs.getString("policy_decision_id"),
        rs.getObject("requested_at", OffsetDateTime.class).toInstant(),
        rs.getString("decided_by"),
        decidedAt == null ? null : decidedAt.toInstant(),
        rs.getString("comment"),
        rs.getString("decision_idempotency_key"));
  }

  private static OffsetDateTime ts(Instant instant) {
    return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }
}
