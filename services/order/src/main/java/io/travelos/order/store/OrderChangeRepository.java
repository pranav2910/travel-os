package io.travelos.order.store;

import io.travelos.common.identity.Principal;
import io.travelos.common.tenant.TenantId;
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
public class OrderChangeRepository {

  private final JdbcClient jdbc;

  public OrderChangeRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public void insert(OrderChangeRecord c) {
    jdbc.sql(
            """
            INSERT INTO order_change (change_id, order_id, tenant_id, disruption_id, idempotency_key, status, previous_status,
              previous_bundle_id, replacement_bundle_id, replacement_offer, currency, incremental_minor, policy_decision_id,
              optimization_run_id, approval_id, external_order_id, record_locator, failure_code, failure_message,
              requested_by, created_at, updated_at)
            VALUES (:id, :order, :tenant, :disruption, :key, :status, :prevStatus, :prevBundle, :bundle,
              CAST(:offer AS jsonb), :currency, :incremental, :pd, :opt, :apr, :ext, :locator, :failureCode,
              :failureMessage, :by, :createdAt, :updatedAt)
            """)
        .param("id", c.changeId())
        .param("order", c.orderId())
        .param("tenant", c.tenant().value())
        .param("disruption", c.disruptionId())
        .param("key", c.idempotencyKey())
        .param("status", c.status().name())
        .param("prevStatus", c.previousStatus().name())
        .param("prevBundle", c.previousBundleId())
        .param("bundle", c.replacementBundleId())
        .param("offer", c.replacementOfferJson())
        .param("currency", c.currency())
        .param("incremental", c.incrementalMinor())
        .param("pd", c.policyDecisionId())
        .param("opt", c.optimizationRunId())
        .param("apr", c.approvalId())
        .param("ext", c.externalOrderId())
        .param("locator", c.recordLocator())
        .param("failureCode", c.failureCode())
        .param("failureMessage", c.failureMessage())
        .param("by", c.requestedBy().id())
        .param("createdAt", Timestamp.from(c.createdAt()))
        .param("updatedAt", Timestamp.from(c.updatedAt()))
        .update();
  }

  public Optional<OrderChangeRecord> findByIdempotencyKey(TenantId tenant, String key) {
    return jdbc.sql("SELECT * FROM order_change WHERE tenant_id = :t AND idempotency_key = :k")
        .param("t", tenant.value())
        .param("k", key)
        .query(OrderChangeRepository::map)
        .optional();
  }

  public Optional<OrderChangeRecord> find(TenantId tenant, String changeId) {
    return jdbc.sql("SELECT * FROM order_change WHERE tenant_id = :t AND change_id = :id")
        .param("t", tenant.value())
        .param("id", changeId)
        .query(OrderChangeRepository::map)
        .optional();
  }

  public List<OrderChangeRecord> byOrder(TenantId tenant, String orderId) {
    return jdbc.sql(
            "SELECT * FROM order_change WHERE tenant_id = :t AND order_id = :o ORDER BY created_at, change_id")
        .param("t", tenant.value())
        .param("o", orderId)
        .query(OrderChangeRepository::map)
        .list();
  }

  public void complete(
      String changeId,
      OrderChangeRecord.Status status,
      @Nullable Long incrementalMinor,
      @Nullable String externalOrderId,
      @Nullable String recordLocator,
      @Nullable String failureCode,
      @Nullable String failureMessage,
      Instant now) {
    jdbc.sql(
            """
            UPDATE order_change SET status = :status, incremental_minor = :incremental,
              external_order_id = coalesce(:ext, external_order_id), record_locator = coalesce(:locator, record_locator),
              failure_code = :failureCode, failure_message = :failureMessage, updated_at = :now
            WHERE change_id = :id
            """)
        .param("status", status.name())
        .param("incremental", incrementalMinor)
        .param("ext", externalOrderId)
        .param("locator", recordLocator)
        .param("failureCode", failureCode)
        .param("failureMessage", failureMessage)
        .param("now", Timestamp.from(now))
        .param("id", changeId)
        .update();
  }

  private static OrderChangeRecord map(ResultSet rs, int i) throws SQLException {
    Long incremental = rs.getObject("incremental_minor", Long.class);
    return new OrderChangeRecord(
        rs.getString("change_id"),
        rs.getString("order_id"),
        TenantId.of(rs.getString("tenant_id")),
        rs.getString("disruption_id"),
        rs.getString("idempotency_key"),
        OrderChangeRecord.Status.valueOf(rs.getString("status")),
        OrderStatus.valueOf(rs.getString("previous_status")),
        rs.getString("previous_bundle_id"),
        rs.getString("replacement_bundle_id"),
        rs.getString("replacement_offer"),
        rs.getString("currency"),
        incremental,
        rs.getString("policy_decision_id"),
        rs.getString("optimization_run_id"),
        rs.getString("approval_id"),
        rs.getString("external_order_id"),
        rs.getString("record_locator"),
        rs.getString("failure_code"),
        rs.getString("failure_message"),
        Principal.parse(rs.getString("requested_by")),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant());
  }
}
