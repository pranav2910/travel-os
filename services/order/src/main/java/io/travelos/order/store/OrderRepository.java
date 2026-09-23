package io.travelos.order.store;

import io.travelos.common.identity.Principal;
import io.travelos.common.money.Money;
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

/** Plain SQL, tenant-scoped everywhere. */
@Repository
public class OrderRepository {

  private static final String ORDER_COLUMNS =
      "order_id, tenant_id, trip_id, traveler_id, bundle_id, supplier, external_order_id, status,"
          + " currency, total_minor, idempotency_key, policy_decision_id, optimization_run_id,"
          + " approval_id, failure_code, failure_message, compensated, created_by, version,"
          + " created_at, updated_at";
  private static final String ITEM_COLUMNS =
      "item_id, position, offer_type, provider, provider_offer_id, offer::text AS offer, status,"
          + " external_ref, record_locator, currency, total_minor, failure_code, updated_at,"
          + " component_id";

  private final JdbcClient jdbc;

  public OrderRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public void insert(OrderRecord o) {
    jdbc.sql(
            """
            INSERT INTO travel_order (order_id, tenant_id, trip_id, traveler_id, bundle_id, supplier,
              status, currency, total_minor, idempotency_key, policy_decision_id, optimization_run_id,
              approval_id, created_by, version, created_at, updated_at)
            VALUES (:orderId, :tenantId, :tripId, :travelerId, :bundleId, :supplier, :status, :currency,
              :total, :key, :policyDecisionId, :optimizationRunId, :approvalId, :createdBy, 0,
              :createdAt, :updatedAt)
            """)
        .param("orderId", o.orderId())
        .param("tenantId", o.tenant().value())
        .param("tripId", o.tripId())
        .param("travelerId", o.travelerId())
        .param("bundleId", o.bundleId())
        .param("supplier", o.supplier())
        .param("status", o.status().name())
        .param("currency", o.total().currency())
        .param("total", o.total().amountMinor())
        .param("key", o.idempotencyKey())
        .param("policyDecisionId", o.policyDecisionId())
        .param("optimizationRunId", o.optimizationRunId())
        .param("approvalId", o.approvalId())
        .param("createdBy", o.createdBy().id())
        .param("createdAt", ts(o.createdAt()))
        .param("updatedAt", ts(o.updatedAt()))
        .update();
    for (OrderRecord.Item item : o.items()) {
      jdbc.sql(
              """
              INSERT INTO order_item (item_id, order_id, tenant_id, position, offer_type, provider,
                provider_offer_id, offer, status, currency, total_minor, updated_at, component_id)
              VALUES (:itemId, :orderId, :tenantId, :position, :type, :provider, :providerOfferId,
                CAST(:offer AS jsonb), :status, :currency, :total, :updatedAt, :componentId)
              """)
          .param("componentId", item.componentId())
          .param("itemId", item.itemId())
          .param("orderId", o.orderId())
          .param("tenantId", o.tenant().value())
          .param("position", item.position())
          .param("type", item.offerType())
          .param("provider", item.provider())
          .param("providerOfferId", item.providerOfferId())
          .param("offer", item.offerJson())
          .param("status", item.status().name())
          .param("currency", item.total().currency())
          .param("total", item.total().amountMinor())
          .param("updatedAt", ts(item.updatedAt()))
          .update();
    }
  }

  public Optional<OrderRecord> find(TenantId tenant, String orderId) {
    return jdbc.sql(
            "SELECT "
                + ORDER_COLUMNS
                + " FROM travel_order WHERE tenant_id = :t AND order_id = :id")
        .param("t", tenant.value())
        .param("id", orderId)
        .query(this::map)
        .optional();
  }

  public Optional<OrderRecord> findByIdempotencyKey(TenantId tenant, String key) {
    return jdbc.sql(
            "SELECT "
                + ORDER_COLUMNS
                + " FROM travel_order WHERE tenant_id = :t AND idempotency_key = :k")
        .param("t", tenant.value())
        .param("k", key)
        .query(this::map)
        .optional();
  }

  /** Impacted-trip detection: the order whose (supplier, external reference) this is, if any. */
  public Optional<OrderRecord> findByExternalRef(
      TenantId tenant, String supplier, String externalOrderId) {
    return jdbc.sql(
            """
            SELECT DISTINCT o.* FROM travel_order o
            LEFT JOIN order_item i ON i.order_id = o.order_id
            WHERE o.tenant_id = :t AND (
              (o.supplier = :s AND o.external_order_id = :e) OR (i.provider = :s AND i.external_ref = :e))
            ORDER BY o.created_at DESC
            LIMIT 1
            """)
        .param("t", tenant.value())
        .param("s", supplier)
        .param("e", externalOrderId)
        .query(this::map)
        .optional();
  }

  public void insertItem(String orderId, TenantId tenant, OrderRecord.Item item) {
    jdbc.sql(
            """
            INSERT INTO order_item (item_id, order_id, tenant_id, position, offer_type, provider, provider_offer_id, offer,
              status, external_ref, record_locator, currency, total_minor, failure_code, updated_at, component_id)
            VALUES (:id, :order, :t, :position, :type, :provider, :offerId, CAST(:offer AS jsonb), :status, :ref,
              :locator, :currency, :total, :failureCode, :now, :component)
            """)
        .param("component", item.componentId())
        .param("id", item.itemId())
        .param("order", orderId)
        .param("t", tenant.value())
        .param("position", item.position())
        .param("type", item.offerType())
        .param("provider", item.provider())
        .param("offerId", item.providerOfferId())
        .param("offer", item.offerJson())
        .param("status", item.status().name())
        .param("ref", item.externalRef())
        .param("locator", item.recordLocator())
        .param("currency", item.total().currency())
        .param("total", item.total().amountMinor())
        .param("failureCode", item.failureCode())
        .param("now", ts(item.updatedAt()))
        .update();
  }

  /** The order now IS the replacement: new bundle, new total, new supplier reference. */
  public boolean replaceItinerary(
      OrderRecord order,
      OrderStatus to,
      String bundleId,
      io.travelos.common.money.Money total,
      @Nullable String externalOrderId,
      String reason,
      Instant now) {
    int rows =
        jdbc.sql(
                """
                UPDATE travel_order SET status = :to, bundle_id = :bundle, currency = :currency, total_minor = :total,
                  external_order_id = coalesce(:external, external_order_id), failure_code = NULL, failure_message = NULL,
                  version = version + 1, updated_at = :now
                WHERE tenant_id = :t AND order_id = :id AND version = :version
                """)
            .param("to", to.name())
            .param("bundle", bundleId)
            .param("currency", total.currency())
            .param("total", total.amountMinor())
            .param("external", externalOrderId)
            .param("now", ts(now))
            .param("t", order.tenant().value())
            .param("id", order.orderId())
            .param("version", order.version())
            .update();
    if (rows == 1) {
      jdbc.sql(
              """
              INSERT INTO order_status_history (order_id, tenant_id, from_status, to_status, reason, occurred_at)
              VALUES (:id, :t, :from, :to, :reason, :now)
              """)
          .param("id", order.orderId())
          .param("t", order.tenant().value())
          .param("from", order.status().name())
          .param("to", to.name())
          .param("reason", reason)
          .param("now", ts(now))
          .update();
    }
    return rows == 1;
  }

  public List<OrderRecord> byTrip(TenantId tenant, String tripId) {
    return jdbc.sql(
            "SELECT "
                + ORDER_COLUMNS
                + " FROM travel_order WHERE tenant_id = :t AND trip_id = :trip ORDER BY created_at")
        .param("t", tenant.value())
        .param("trip", tripId)
        .query(this::map)
        .list();
  }

  /** Optimistic: only moves the order if nobody else did since it was read. */
  public boolean transition(
      OrderRecord order,
      OrderStatus to,
      @Nullable String reason,
      @Nullable String externalOrderId,
      @Nullable String failureCode,
      @Nullable String failureMessage,
      boolean compensated,
      Instant now) {
    int rows =
        jdbc.sql(
                """
                UPDATE travel_order SET status = :to, external_order_id = coalesce(:external, external_order_id),
                  failure_code = :failureCode, failure_message = :failureMessage, compensated = :compensated,
                  version = version + 1, updated_at = :now
                WHERE tenant_id = :t AND order_id = :id AND version = :version
                """)
            .param("to", to.name())
            .param("external", externalOrderId)
            .param("failureCode", failureCode)
            .param("failureMessage", failureMessage)
            .param("compensated", compensated)
            .param("now", ts(now))
            .param("t", order.tenant().value())
            .param("id", order.orderId())
            .param("version", order.version())
            .update();
    if (rows == 1) {
      jdbc.sql(
              """
              INSERT INTO order_status_history (order_id, tenant_id, from_status, to_status, reason, occurred_at)
              VALUES (:id, :t, :from, :to, :reason, :now)
              """)
          .param("id", order.orderId())
          .param("t", order.tenant().value())
          .param("from", order.status().name())
          .param("to", to.name())
          .param("reason", reason)
          .param("now", ts(now))
          .update();
    }
    return rows == 1;
  }

  public void updateItem(
      String itemId,
      OrderRecord.ItemStatus status,
      @Nullable String externalRef,
      @Nullable String recordLocator,
      @Nullable String failureCode,
      Instant now) {
    jdbc.sql(
            """
            UPDATE order_item SET status = :status, external_ref = coalesce(:ref, external_ref),
              record_locator = coalesce(:locator, record_locator), failure_code = :failureCode, updated_at = :now
            WHERE item_id = :id
            """)
        .param("status", status.name())
        .param("ref", externalRef)
        .param("locator", recordLocator)
        .param("failureCode", failureCode)
        .param("now", ts(now))
        .param("id", itemId)
        .update();
  }

  /** What the supplier gave back when the item was released; summed into the cancelled event. */
  public void recordItemRefund(String itemId, Money refund, Instant now) {
    jdbc.sql(
            """
            UPDATE order_item SET refund_currency = :currency, refund_minor = :minor, updated_at = :now
            WHERE item_id = :id
            """)
        .param("currency", refund.currency())
        .param("minor", refund.amountMinor())
        .param("now", ts(now))
        .param("id", itemId)
        .update();
  }

  /** The recorded refunds of an order, summed; empty when none was recorded or currencies mix. */
  public Optional<Money> refundsOf(String orderId) {
    List<Money> refunds =
        jdbc.sql(
                "SELECT refund_currency, refund_minor FROM order_item"
                    + " WHERE order_id = :id AND refund_minor IS NOT NULL")
            .param("id", orderId)
            .query((rs, n) -> Money.of(rs.getString(1), rs.getLong(2)))
            .list();
    Money total = null;
    for (Money m : refunds) {
      if (total == null) {
        total = m;
      } else if (total.currency().equals(m.currency())) {
        total = total.plus(m);
      } else {
        return Optional.empty();
      }
    }
    return Optional.ofNullable(total);
  }

  private OrderRecord map(ResultSet rs, int rowNum) throws SQLException {
    String orderId = rs.getString("order_id");
    List<OrderRecord.Item> items =
        jdbc.sql(
                "SELECT "
                    + ITEM_COLUMNS
                    + " FROM order_item WHERE order_id = :id ORDER BY position")
            .param("id", orderId)
            .query(OrderRepository::mapItem)
            .list();
    return new OrderRecord(
        orderId,
        TenantId.of(rs.getString("tenant_id")),
        rs.getString("trip_id"),
        rs.getString("traveler_id"),
        rs.getString("bundle_id"),
        rs.getString("supplier"),
        rs.getString("external_order_id"),
        OrderStatus.valueOf(rs.getString("status")),
        Money.of(rs.getString("currency"), rs.getLong("total_minor")),
        rs.getString("idempotency_key"),
        rs.getString("policy_decision_id"),
        rs.getString("optimization_run_id"),
        rs.getString("approval_id"),
        rs.getString("failure_code"),
        rs.getString("failure_message"),
        rs.getBoolean("compensated"),
        Principal.parse(rs.getString("created_by")),
        rs.getLong("version"),
        instant(rs, "created_at"),
        instant(rs, "updated_at"),
        items);
  }

  private static OrderRecord.Item mapItem(ResultSet rs, int rowNum) throws SQLException {
    return new OrderRecord.Item(
        rs.getString("item_id"),
        rs.getInt("position"),
        rs.getString("offer_type"),
        rs.getString("provider"),
        rs.getString("provider_offer_id"),
        rs.getString("offer"),
        OrderRecord.ItemStatus.valueOf(rs.getString("status")),
        rs.getString("external_ref"),
        rs.getString("record_locator"),
        Money.of(rs.getString("currency"), rs.getLong("total_minor")),
        rs.getString("failure_code"),
        instant(rs, "updated_at"),
        rs.getString("component_id"));
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    return rs.getObject(column, OffsetDateTime.class).toInstant();
  }

  private static OffsetDateTime ts(Instant instant) {
    return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }
}
