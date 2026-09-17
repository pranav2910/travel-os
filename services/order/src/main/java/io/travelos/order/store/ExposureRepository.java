package io.travelos.order.store;

import io.travelos.common.money.Money;
import io.travelos.common.tenant.TenantId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ExposureRepository {
  private final JdbcClient jdbc;

  public ExposureRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public void insert(TenantId tenant, ExposureRecord e) {
    jdbc.sql(
            """
            INSERT INTO order_exposure (exposure_id, order_id, tenant_id, item_id, component_id, provider,
              external_ref, currency, amount_minor, reason, detail, status, created_at)
            VALUES (:id, :order, :tenant, :item, :component, :provider, :ref, :currency, :amount, :reason,
              :detail, :status, :createdAt)
            """)
        .param("id", e.exposureId())
        .param("order", e.orderId())
        .param("tenant", tenant.value())
        .param("item", e.itemId())
        .param("component", e.componentId())
        .param("provider", e.provider())
        .param("ref", e.externalRef())
        .param("currency", e.amount().currency())
        .param("amount", e.amount().amountMinor())
        .param("reason", e.reason())
        .param("detail", e.detail())
        .param("status", e.status().name())
        .param("createdAt", Timestamp.from(e.createdAt()))
        .update();
  }

  /** Open exposures across every tenant: the platform-wide number a person still owes. */
  public long countOpen() {
    Long n =
        jdbc.sql("SELECT count(*) FROM order_exposure WHERE status = 'OPEN'")
            .query(Long.class)
            .single();
    return n == null ? 0L : n;
  }

  /** The tenant's exposures at one status, oldest first: what Finance still has to close. */
  public List<ExposureRecord> byTenantAndStatus(
      TenantId tenant, ExposureRecord.Status status, int limit) {
    return jdbc.sql(
            "SELECT * FROM order_exposure WHERE tenant_id = :t AND status = :s ORDER BY created_at LIMIT :n")
        .param("t", tenant.value())
        .param("s", status.name())
        .param("n", limit)
        .query(ExposureRepository::map)
        .list();
  }

  public List<ExposureRecord> byOrder(TenantId tenant, String orderId) {
    return jdbc.sql(
            "SELECT * FROM order_exposure WHERE tenant_id = :t AND order_id = :o ORDER BY created_at")
        .param("t", tenant.value())
        .param("o", orderId)
        .query(ExposureRepository::map)
        .list();
  }

  public Optional<ExposureRecord> find(TenantId tenant, String exposureId) {
    return jdbc.sql("SELECT * FROM order_exposure WHERE tenant_id = :t AND exposure_id = :id")
        .param("t", tenant.value())
        .param("id", exposureId)
        .query(ExposureRepository::map)
        .optional();
  }

  /** Resolves an OPEN exposure once; a second call with the same key changes nothing. */
  public boolean resolve(
      TenantId tenant, String exposureId, String by, String resolution, String key, Instant now) {
    return jdbc.sql(
                """
            UPDATE order_exposure SET status = 'RESOLVED', resolved_by = :by, resolution = :resolution,
              resolution_idempotency_key = :key, resolved_at = :now
            WHERE tenant_id = :t AND exposure_id = :id AND status = 'OPEN'
            """)
            .param("by", by)
            .param("resolution", resolution)
            .param("key", key)
            .param("now", Timestamp.from(now))
            .param("t", tenant.value())
            .param("id", exposureId)
            .update()
        == 1;
  }

  private static ExposureRecord map(ResultSet rs, int rowNum) throws SQLException {
    Timestamp resolved = rs.getTimestamp("resolved_at");
    return new ExposureRecord(
        rs.getString("exposure_id"),
        rs.getString("order_id"),
        rs.getString("item_id"),
        rs.getString("component_id"),
        rs.getString("provider"),
        rs.getString("external_ref"),
        Money.of(rs.getString("currency"), rs.getLong("amount_minor")),
        rs.getString("reason"),
        rs.getString("detail"),
        ExposureRecord.Status.valueOf(rs.getString("status")),
        rs.getString("resolved_by"),
        rs.getString("resolution"),
        rs.getString("resolution_idempotency_key"),
        rs.getTimestamp("created_at").toInstant(),
        resolved == null ? null : resolved.toInstant());
  }
}
