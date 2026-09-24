package io.travelos.travelcore.approval;

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

@Repository
public class ApprovalRepository {

  private static final String COLUMNS =
      "approval_id, tenant_id, trip_id, required_role, status, policy_decision_id, requested_at,"
          + " decided_by, decided_at, comment, decision_idempotency_key, step, chain_length,"
          + " chain_roles, expires_at, escalated_at, escalated_to_role, on_behalf_of";

  private final JdbcClient jdbc;

  public ApprovalRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public void insert(Approval a) {
    jdbc.sql(
            """
            INSERT INTO approval (approval_id, tenant_id, trip_id, required_role, status,
              policy_decision_id, requested_at, step, chain_length, chain_roles, expires_at)
            VALUES (:id, :tenant, :trip, :role, :status, :policyDecisionId, :requestedAt, :step, :chain,
              :roles, :expiresAt)
            """)
        .param("id", a.approvalId())
        .param("tenant", a.tenant().value())
        .param("trip", a.tripId())
        .param("role", a.requiredRole())
        .param("status", a.status().name())
        .param("policyDecisionId", a.policyDecisionId())
        .param("requestedAt", ts(a.requestedAt()))
        .param("step", a.step())
        .param("chain", a.chainLength())
        .param("roles", String.join(",", a.chainRoles()))
        .param("expiresAt", a.expiresAt() == null ? null : ts(a.expiresAt()))
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
    return decide(pending, decision, decidedBy, comment, idempotencyKey, null, now);
  }

  /** Phase 7: the same, naming whose authority a delegate exercised. */
  public boolean decide(
      Approval pending,
      Approval.Status decision,
      String decidedBy,
      @Nullable String comment,
      String idempotencyKey,
      @Nullable String onBehalfOf,
      Instant now) {
    return jdbc.sql(
                """
                UPDATE approval SET status = :status, decided_by = :by, decided_at = :at, comment = :comment,
                  decision_idempotency_key = :key, on_behalf_of = :onBehalfOf
                WHERE tenant_id = :t AND approval_id = :id AND status = 'PENDING'
                """)
            .param("status", decision.name())
            .param("by", decidedBy)
            .param("at", ts(now))
            .param("comment", comment)
            .param("key", idempotencyKey)
            .param("onBehalfOf", onBehalfOf)
            .param("t", pending.tenant().value())
            .param("id", pending.approvalId())
            .update()
        == 1;
  }

  /** Phase 7: every step of a trip's approvals, oldest first. */
  public List<Approval> listForTrip(TenantId tenant, String tripId) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM approval WHERE tenant_id = :t AND trip_id = :trip ORDER BY requested_at, step")
        .param("t", tenant.value())
        .param("trip", tripId)
        .query(ApprovalRepository::map)
        .list();
  }

  /** Phase 7: pending approvals past their expiry, locked so one sweep at a time handles each. */
  public List<Approval> expiredPending(Instant now, int limit) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM approval WHERE status = 'PENDING' AND expires_at IS NOT NULL AND expires_at < :now"
                + " ORDER BY expires_at LIMIT :n FOR UPDATE SKIP LOCKED")
        .param("now", ts(now))
        .param("n", limit)
        .query(ApprovalRepository::map)
        .list();
  }

  /** Phase 7: an unanswered step goes to a wider role and gets a fresh expiry. */
  public boolean escalate(Approval pending, String toRole, Instant newExpiry, Instant now) {
    return jdbc.sql(
                """
                UPDATE approval SET escalated_at = :at, escalated_to_role = :role, expires_at = :expiry
                WHERE tenant_id = :t AND approval_id = :id AND status = 'PENDING' AND escalated_at IS NULL
                """)
            .param("at", ts(now))
            .param("role", toRole)
            .param("expiry", ts(newExpiry))
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
        rs.getString("decision_idempotency_key"),
        rs.getInt("step"),
        rs.getInt("chain_length"),
        rs.getString("chain_roles") == null
            ? List.of()
            : List.of(rs.getString("chain_roles").split(",")),
        optional(rs, "expires_at"),
        optional(rs, "escalated_at"),
        rs.getString("escalated_to_role"),
        rs.getString("on_behalf_of"));
  }

  private static @Nullable Instant optional(ResultSet rs, String column) throws SQLException {
    OffsetDateTime v = rs.getObject(column, OffsetDateTime.class);
    return v == null ? null : v.toInstant();
  }

  private static OffsetDateTime ts(Instant instant) {
    return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }
}
