package io.travelos.learning.store;

import io.travelos.common.tenant.TenantId;
import io.travelos.learning.model.OrderItemRef;
import io.travelos.learning.model.TripRef;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class TripIndexRepository {
  private final JdbcClient jdbc;

  public TripIndexRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<TripRef> find(TenantId tenant, String tripId) {
    return jdbc.sql("SELECT * FROM trip_index WHERE tenant_id = :t AND trip_id = :id")
        .param("t", tenant.value())
        .param("id", tripId)
        .query(TripIndexRepository::mapTrip)
        .optional();
  }

  /** Upsert: a null argument leaves the stored value alone. */
  public void upsert(
      TenantId tenant,
      String tripId,
      @Nullable String travelerId,
      @Nullable String orderId,
      @Nullable String status,
      @Nullable Instant bookedAt,
      @Nullable Instant completedAt,
      Instant now) {
    jdbc.sql(
            """
            INSERT INTO trip_index (tenant_id, trip_id, traveler_id, order_id, status, booked_at, completed_at, updated_at)
            VALUES (:t, :id, :traveler, :order, COALESCE(:status, 'UNKNOWN'), :booked, :completed, :now)
            ON CONFLICT (tenant_id, trip_id) DO UPDATE SET
              traveler_id = COALESCE(EXCLUDED.traveler_id, trip_index.traveler_id),
              order_id = COALESCE(EXCLUDED.order_id, trip_index.order_id),
              status = COALESCE(:status, trip_index.status),
              booked_at = COALESCE(EXCLUDED.booked_at, trip_index.booked_at),
              completed_at = COALESCE(EXCLUDED.completed_at, trip_index.completed_at),
              updated_at = EXCLUDED.updated_at
            """)
        .param("t", tenant.value())
        .param("id", tripId)
        .param("traveler", travelerId)
        .param("order", orderId)
        .param("status", status)
        .param("booked", Rows.ts(bookedAt))
        .param("completed", Rows.ts(completedAt))
        .param("now", Rows.ts(now))
        .update();
  }

  public void upsertItem(TenantId tenant, OrderItemRef item, Instant now) {
    jdbc.sql(
            """
            INSERT INTO order_item (tenant_id, order_id, item_id, trip_id, component_id, item_type, provider, supplier_key, status, updated_at)
            VALUES (:t, :order, :item, :trip, :component, :type, :provider, :key, :status, :now)
            ON CONFLICT (tenant_id, order_id, item_id) DO UPDATE SET
              component_id = COALESCE(EXCLUDED.component_id, order_item.component_id),
              provider = COALESCE(EXCLUDED.provider, order_item.provider),
              supplier_key = COALESCE(EXCLUDED.supplier_key, order_item.supplier_key),
              status = EXCLUDED.status, updated_at = EXCLUDED.updated_at
            """)
        .param("t", tenant.value())
        .param("order", item.orderId())
        .param("item", item.itemId())
        .param("trip", item.tripId())
        .param("component", item.componentId())
        .param("type", item.type())
        .param("provider", item.provider())
        .param("key", item.supplierKey())
        .param("status", item.status())
        .param("now", Rows.ts(now))
        .update();
  }

  public List<OrderItemRef> items(TenantId tenant, String tripId) {
    return jdbc.sql(
            "SELECT * FROM order_item WHERE tenant_id = :t AND trip_id = :trip ORDER BY item_id")
        .param("t", tenant.value())
        .param("trip", tripId)
        .query(TripIndexRepository::mapItem)
        .list();
  }

  public Optional<OrderItemRef> item(TenantId tenant, String orderId, String itemId) {
    return jdbc.sql(
            "SELECT * FROM order_item WHERE tenant_id = :t AND order_id = :o AND item_id = :i")
        .param("t", tenant.value())
        .param("o", orderId)
        .param("i", itemId)
        .query(TripIndexRepository::mapItem)
        .optional();
  }

  static TripRef mapTrip(ResultSet rs, int i) throws SQLException {
    return new TripRef(
        TenantId.of(rs.getString("tenant_id")),
        rs.getString("trip_id"),
        rs.getString("traveler_id"),
        rs.getString("order_id"),
        rs.getString("status"),
        Rows.instant(rs, "booked_at"),
        Rows.instant(rs, "completed_at"));
  }

  static OrderItemRef mapItem(ResultSet rs, int i) throws SQLException {
    return new OrderItemRef(
        rs.getString("order_id"),
        rs.getString("item_id"),
        rs.getString("trip_id"),
        rs.getString("component_id"),
        rs.getString("item_type"),
        rs.getString("provider"),
        rs.getString("supplier_key"),
        rs.getString("status"));
  }
}
