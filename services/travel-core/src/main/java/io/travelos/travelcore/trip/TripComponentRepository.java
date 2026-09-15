package io.travelos.travelcore.trip;

import io.travelos.common.money.Money;
import io.travelos.common.tenant.TenantId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class TripComponentRepository {
  private final JdbcClient jdbc;

  public TripComponentRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** Idempotent: the same state twice is one row; a newer state replaces the old. */
  public void upsert(TenantId tenant, String tripId, TripComponent c) {
    jdbc.sql(
            """
            INSERT INTO trip_component (trip_id, tenant_id, component_id, type, status, offer_id, provider,
              external_ref, total_currency, total_minor, failure_code, summary, position, updated_at)
            VALUES (:tripId, :tenant, :componentId, :type, :status, :offerId, :provider, :externalRef,
              :currency, :minor, :failureCode, :summary, :position, :updatedAt)
            ON CONFLICT (trip_id, component_id) DO UPDATE SET
              type = EXCLUDED.type, status = EXCLUDED.status, offer_id = EXCLUDED.offer_id,
              provider = EXCLUDED.provider, external_ref = EXCLUDED.external_ref,
              total_currency = EXCLUDED.total_currency, total_minor = EXCLUDED.total_minor,
              failure_code = EXCLUDED.failure_code, summary = EXCLUDED.summary,
              position = EXCLUDED.position, updated_at = EXCLUDED.updated_at
            """)
        .param("tripId", tripId)
        .param("tenant", tenant.value())
        .param("componentId", c.componentId())
        .param("type", c.type())
        .param("status", c.status())
        .param("offerId", c.offerId())
        .param("provider", c.provider())
        .param("externalRef", c.externalRef())
        .param("currency", c.total() == null ? null : c.total().currency())
        .param("minor", c.total() == null ? null : c.total().amountMinor())
        .param("failureCode", c.failureCode())
        .param("summary", c.summary())
        .param("position", c.position())
        .param("updatedAt", OffsetDateTime.ofInstant(c.updatedAt(), ZoneOffset.UTC))
        .update();
  }

  public List<TripComponent> list(TenantId tenant, String tripId) {
    return jdbc.sql(
            "SELECT * FROM trip_component WHERE tenant_id = :tenant AND trip_id = :tripId"
                + " ORDER BY position, component_id")
        .param("tenant", tenant.value())
        .param("tripId", tripId)
        .query(TripComponentRepository::map)
        .list();
  }

  private static TripComponent map(ResultSet rs, int rowNum) throws SQLException {
    String currency = rs.getString("total_currency");
    return new TripComponent(
        rs.getString("component_id"),
        rs.getString("type"),
        rs.getString("status"),
        rs.getString("offer_id"),
        rs.getString("provider"),
        rs.getString("external_ref"),
        currency == null ? null : Money.of(currency, rs.getLong("total_minor")),
        rs.getString("failure_code"),
        rs.getString("summary"),
        rs.getInt("position"),
        instant(rs, "updated_at"));
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    OffsetDateTime t = rs.getObject(column, OffsetDateTime.class);
    return t.toInstant();
  }
}
