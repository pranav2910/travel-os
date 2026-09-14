package io.travelos.supplier.notification;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The gateway's memory of what it booked, so a supplier notice can be tied back to a trip. */
@Repository
public class SupplierOrderRefRepository {

  public record Ref(
      String provider,
      String externalOrderId,
      String tenantId,
      String correlationId,
      @Nullable String recordLocator,
      Instant bookedAt) {}

  private final JdbcClient jdbc;

  public SupplierOrderRefRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public void remember(Ref ref) {
    jdbc.sql(
            """
            INSERT INTO supplier_order_ref (provider, external_order_id, tenant_id, correlation_id, record_locator, booked_at)
            VALUES (:provider, :id, :tenant, :correlation, :locator, :at)
            ON CONFLICT (provider, external_order_id) DO NOTHING
            """)
        .param("provider", ref.provider())
        .param("id", ref.externalOrderId())
        .param("tenant", ref.tenantId())
        .param("correlation", ref.correlationId())
        .param("locator", ref.recordLocator())
        .param("at", Timestamp.from(ref.bookedAt()))
        .update();
  }

  public Optional<Ref> find(String provider, String externalOrderId) {
    return jdbc.sql(
            "SELECT * FROM supplier_order_ref WHERE provider = :provider AND external_order_id = :id")
        .param("provider", provider)
        .param("id", externalOrderId)
        .query(
            (rs, i) ->
                new Ref(
                    rs.getString("provider"),
                    rs.getString("external_order_id"),
                    rs.getString("tenant_id"),
                    rs.getString("correlation_id"),
                    rs.getString("record_locator"),
                    rs.getTimestamp("booked_at").toInstant()))
        .optional();
  }
}
