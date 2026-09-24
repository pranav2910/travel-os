package io.travelos.assistance.store;

import io.travelos.common.tenant.TenantId;
import java.time.Instant;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class TripIndexRepository {
  public record TripRef(String tripId, @Nullable String travelerId, @Nullable String orderId) {}

  private final JdbcClient jdbc;

  public TripIndexRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<TripRef> find(TenantId tenant, String tripId) {
    return jdbc.sql(
            "SELECT trip_id, traveler_id, order_id FROM trip_index WHERE tenant_id = :t AND trip_id = :id")
        .param("t", tenant.value())
        .param("id", tripId)
        .query(
            (rs, i) ->
                new TripRef(
                    rs.getString("trip_id"), rs.getString("traveler_id"), rs.getString("order_id")))
        .optional();
  }

  /** Upsert: a null argument leaves the stored value alone. */
  public void upsert(
      TenantId tenant,
      String tripId,
      @Nullable String travelerId,
      @Nullable String orderId,
      Instant now) {
    jdbc.sql(
            """
            INSERT INTO trip_index (tenant_id, trip_id, traveler_id, order_id, updated_at)
            VALUES (:t, :id, :traveler, :order, :now)
            ON CONFLICT (tenant_id, trip_id) DO UPDATE SET
              traveler_id = COALESCE(EXCLUDED.traveler_id, trip_index.traveler_id),
              order_id = COALESCE(EXCLUDED.order_id, trip_index.order_id),
              updated_at = EXCLUDED.updated_at
            """)
        .param("t", tenant.value())
        .param("id", tripId)
        .param("traveler", travelerId)
        .param("order", orderId)
        .param("now", Rows.ts(now))
        .update();
  }
}
