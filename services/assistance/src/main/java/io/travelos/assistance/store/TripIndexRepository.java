package io.travelos.assistance.store;

import io.travelos.common.tenant.TenantId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class TripIndexRepository {
  public record TripRef(
      String tripId,
      @Nullable String travelerId,
      @Nullable String orderId,
      @Nullable String travelerEmail,
      @Nullable String travelerName,
      @Nullable String status,
      @Nullable String origin,
      @Nullable String destination,
      @Nullable Instant departsAt,
      @Nullable Instant returnsAt,
      List<String> cities) {}

  /** Phase 8: what an event told us about the trip; nulls leave the stored value alone. */
  public record Journey(
      @Nullable String travelerEmail,
      @Nullable String travelerName,
      @Nullable String status,
      @Nullable String origin,
      @Nullable String destination,
      @Nullable Instant departsAt,
      @Nullable Instant returnsAt,
      @Nullable List<String> cities) {
    public static final Journey NONE = new Journey(null, null, null, null, null, null, null, null);
  }

  private final JdbcClient jdbc;

  public TripIndexRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<TripRef> find(TenantId tenant, String tripId) {
    return jdbc.sql("SELECT * FROM trip_index WHERE tenant_id = :t AND trip_id = :id")
        .param("t", tenant.value())
        .param("id", tripId)
        .query(TripIndexRepository::map)
        .optional();
  }

  public void upsert(
      TenantId tenant,
      String tripId,
      @Nullable String travelerId,
      @Nullable String orderId,
      Instant now) {
    upsert(tenant, tripId, travelerId, orderId, Journey.NONE, now);
  }

  /** Upsert: a null argument leaves the stored value alone. */
  public void upsert(
      TenantId tenant,
      String tripId,
      @Nullable String travelerId,
      @Nullable String orderId,
      Journey j,
      Instant now) {
    jdbc.sql(
            """
            INSERT INTO trip_index (tenant_id, trip_id, traveler_id, order_id, updated_at, traveler_email, traveler_name,
              status, origin, destination, departs_at, returns_at, cities)
            VALUES (:t, :id, :traveler, :order, :now, :email, :name, :status, :origin, :destination, :departs, :returns,
              CAST(:cities AS jsonb))
            ON CONFLICT (tenant_id, trip_id) DO UPDATE SET
              traveler_id = COALESCE(EXCLUDED.traveler_id, trip_index.traveler_id),
              order_id = COALESCE(EXCLUDED.order_id, trip_index.order_id),
              traveler_email = COALESCE(EXCLUDED.traveler_email, trip_index.traveler_email),
              traveler_name = COALESCE(EXCLUDED.traveler_name, trip_index.traveler_name),
              status = CASE
                WHEN EXCLUDED.status IS NULL THEN trip_index.status
                WHEN trip_index.status IN ('BOOKED', 'CANCELLED', 'FAILED', 'COMPLETED') AND EXCLUDED.status = 'SUBMITTED' THEN trip_index.status
                ELSE EXCLUDED.status END,
              origin = COALESCE(EXCLUDED.origin, trip_index.origin),
              destination = COALESCE(EXCLUDED.destination, trip_index.destination),
              departs_at = COALESCE(EXCLUDED.departs_at, trip_index.departs_at),
              returns_at = COALESCE(EXCLUDED.returns_at, trip_index.returns_at),
              cities = COALESCE(EXCLUDED.cities, trip_index.cities),
              updated_at = EXCLUDED.updated_at
            """)
        .param("t", tenant.value())
        .param("id", tripId)
        .param("traveler", travelerId)
        .param("order", orderId)
        .param("now", Rows.ts(now))
        .param("email", j.travelerEmail())
        .param("name", j.travelerName())
        .param("status", j.status())
        .param("origin", j.origin())
        .param("destination", j.destination())
        .param("departs", Rows.ts(j.departsAt()))
        .param("returns", Rows.ts(j.returnsAt()))
        .param("cities", j.cities() == null ? null : Rows.json(j.cities()))
        .update();
  }

  /** Phase 8: booked trips whose window overlaps [from, until]; the caller matches the places. */
  public List<TripRef> bookedOverlapping(TenantId tenant, Instant from, Instant until) {
    return jdbc.sql(
            "SELECT * FROM trip_index WHERE tenant_id = :t AND status IN ('BOOKED', 'BOOKING', 'COMPLETED')"
                + " AND departs_at IS NOT NULL AND departs_at <= :until AND COALESCE(returns_at, departs_at) >= :from")
        .param("t", tenant.value())
        .param("from", Rows.ts(from))
        .param("until", Rows.ts(until))
        .query(TripIndexRepository::map)
        .list();
  }

  private static TripRef map(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
    return new TripRef(
        rs.getString("trip_id"),
        rs.getString("traveler_id"),
        rs.getString("order_id"),
        rs.getString("traveler_email"),
        rs.getString("traveler_name"),
        rs.getString("status"),
        rs.getString("origin"),
        rs.getString("destination"),
        Rows.instant(rs, "departs_at"),
        Rows.instant(rs, "returns_at"),
        Rows.strings(rs, "cities"));
  }
}
