package io.travelos.travelcore.trip;

import io.travelos.common.money.Money;
import io.travelos.common.tenant.TenantId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class PurchaseAuthorizationRepository {
  private final JdbcClient jdbc;

  public PurchaseAuthorizationRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** 1 when inserted; 0 when another ACTIVE authorization (or the same key) already exists. */
  public int insert(PurchaseAuthorization a) {
    return jdbc.sql(
            """
            INSERT INTO purchase_authorization (authorization_id, tenant_id, trip_id, status, basis, authorized_by,
              bundle_id, total_currency, total_minor, conditions, traveler_id, profile_version, trip_version,
              policy_decision_id, idempotency_key, expires_at, created_at, updated_at)
            VALUES (:id, :t, :trip, :status, :basis, :by, :bundle, :cur, :minor, :conditions, :traveler, :pv, :tv,
              :pd, :key, :expires, :created, :updated)
            ON CONFLICT DO NOTHING
            """)
        .param("id", a.authorizationId())
        .param("t", a.tenant().value())
        .param("trip", a.tripId())
        .param("status", a.status().name())
        .param("basis", a.basis().name())
        .param("by", a.authorizedBy())
        .param("bundle", a.bundleId())
        .param("cur", a.total().currency())
        .param("minor", a.total().amountMinor())
        .param("conditions", a.conditions())
        .param("traveler", a.travelerId())
        .param("pv", a.profileVersion())
        .param("tv", a.tripVersion())
        .param("pd", a.policyDecisionId())
        .param("key", a.idempotencyKey())
        .param("expires", ts(a.expiresAt()))
        .param("created", ts(a.createdAt()))
        .param("updated", ts(a.updatedAt()))
        .update();
  }

  public Optional<PurchaseAuthorization> find(TenantId tenant, String authorizationId) {
    return jdbc.sql(
            "SELECT * FROM purchase_authorization WHERE tenant_id = :t AND authorization_id = :id")
        .param("t", tenant.value())
        .param("id", authorizationId)
        .query(PurchaseAuthorizationRepository::map)
        .optional();
  }

  public Optional<PurchaseAuthorization> findByIdempotencyKey(TenantId tenant, String key) {
    return jdbc.sql(
            "SELECT * FROM purchase_authorization WHERE tenant_id = :t AND idempotency_key = :key")
        .param("t", tenant.value())
        .param("key", key)
        .query(PurchaseAuthorizationRepository::map)
        .optional();
  }

  public Optional<PurchaseAuthorization> active(TenantId tenant, String tripId) {
    return jdbc.sql(
            "SELECT * FROM purchase_authorization WHERE tenant_id = :t AND trip_id = :trip AND status = 'ACTIVE'")
        .param("t", tenant.value())
        .param("trip", tripId)
        .query(PurchaseAuthorizationRepository::map)
        .optional();
  }

  /** Newest first, every status: the audit trail of who authorized what, and what became of it. */
  public List<PurchaseAuthorization> history(TenantId tenant, String tripId) {
    return jdbc.sql(
            "SELECT * FROM purchase_authorization WHERE tenant_id = :t AND trip_id = :trip ORDER BY created_at DESC, authorization_id DESC")
        .param("t", tenant.value())
        .param("trip", tripId)
        .query(PurchaseAuthorizationRepository::map)
        .list();
  }

  public boolean supersede(
      TenantId tenant, String authorizationId, @Nullable String by, String reason, Instant now) {
    return jdbc.sql(
                """
                UPDATE purchase_authorization SET status = 'SUPERSEDED', superseded_by = :by, superseded_reason = :reason,
                  updated_at = :now WHERE tenant_id = :t AND authorization_id = :id AND status = 'ACTIVE'
                """)
            .param("by", by)
            .param("reason", reason)
            .param("now", ts(now))
            .param("t", tenant.value())
            .param("id", authorizationId)
            .update()
        == 1;
  }

  public boolean revoke(TenantId tenant, String authorizationId, String reason, Instant now) {
    return jdbc.sql(
                """
                UPDATE purchase_authorization SET status = 'REVOKED', superseded_reason = :reason, updated_at = :now
                WHERE tenant_id = :t AND authorization_id = :id AND status = 'ACTIVE'
                """)
            .param("reason", reason)
            .param("now", ts(now))
            .param("t", tenant.value())
            .param("id", authorizationId)
            .update()
        == 1;
  }

  /** Exactly one caller wins: the row moves ACTIVE -> CONSUMED once. */
  public boolean consume(
      TenantId tenant, String authorizationId, String orderAttempt, Instant now) {
    return jdbc.sql(
                """
                UPDATE purchase_authorization SET status = 'CONSUMED', consumed_order_attempt = :attempt, updated_at = :now
                WHERE tenant_id = :t AND authorization_id = :id AND status = 'ACTIVE'
                """)
            .param("attempt", orderAttempt)
            .param("now", ts(now))
            .param("t", tenant.value())
            .param("id", authorizationId)
            .update()
        == 1;
  }

  private static PurchaseAuthorization map(ResultSet rs, int i) throws SQLException {
    return new PurchaseAuthorization(
        rs.getString("authorization_id"),
        TenantId.of(rs.getString("tenant_id")),
        rs.getString("trip_id"),
        PurchaseAuthorization.Status.valueOf(rs.getString("status")),
        PurchaseAuthorization.Basis.valueOf(rs.getString("basis")),
        rs.getString("authorized_by"),
        rs.getString("bundle_id"),
        Money.of(rs.getString("total_currency"), rs.getLong("total_minor")),
        rs.getString("conditions"),
        rs.getString("traveler_id"),
        rs.getLong("profile_version"),
        rs.getLong("trip_version"),
        rs.getString("policy_decision_id"),
        rs.getString("idempotency_key"),
        instant(rs, "expires_at"),
        instant(rs, "created_at"),
        instant(rs, "updated_at"),
        rs.getString("consumed_order_attempt"),
        rs.getString("superseded_by"),
        rs.getString("superseded_reason"));
  }

  private static @Nullable Instant instant(ResultSet rs, String column) throws SQLException {
    OffsetDateTime v = rs.getObject(column, OffsetDateTime.class);
    return v == null ? null : v.toInstant();
  }

  private static @Nullable Timestamp ts(@Nullable Instant instant) {
    return instant == null ? null : Timestamp.from(instant);
  }
}
