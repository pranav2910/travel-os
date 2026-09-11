package io.travelos.supplier.sandbox;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/** The sandbox supplier's order ledger. */
@Repository
public class SandboxOrderRepository {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  public record SandboxOrder(
      String externalOrderId,
      String tenantId,
      String idempotencyKey,
      String providerOfferId,
      String recordLocator,
      String status,
      String currency,
      long chargedMinor,
      List<String> passengers,
      List<String> ticketNumbers,
      Instant createdAt,
      Instant updatedAt) {}

  private final JdbcClient jdbc;

  public SandboxOrderRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public void insert(SandboxOrder o) {
    jdbc.sql(
            """
            INSERT INTO sandbox_order (external_order_id, tenant_id, idempotency_key, provider_offer_id,
              record_locator, status, currency, charged_minor, passengers, ticket_numbers, created_at, updated_at)
            VALUES (:id, :tenant, :key, :offer, :locator, :status, :currency, :charged,
              CAST(:passengers AS jsonb), CAST(:tickets AS jsonb), :createdAt, :updatedAt)
            """)
        .param("id", o.externalOrderId())
        .param("tenant", o.tenantId())
        .param("key", o.idempotencyKey())
        .param("offer", o.providerOfferId())
        .param("locator", o.recordLocator())
        .param("status", o.status())
        .param("currency", o.currency())
        .param("charged", o.chargedMinor())
        .param("passengers", JSON.writeValueAsString(o.passengers()))
        .param("tickets", JSON.writeValueAsString(o.ticketNumbers()))
        .param("createdAt", ts(o.createdAt()))
        .param("updatedAt", ts(o.updatedAt()))
        .update();
  }

  public Optional<SandboxOrder> byIdempotencyKey(String tenantId, String key) {
    return jdbc.sql(
            "SELECT * FROM sandbox_order WHERE tenant_id = :tenant AND idempotency_key = :key")
        .param("tenant", tenantId)
        .param("key", key)
        .query(SandboxOrderRepository::map)
        .optional();
  }

  public Optional<SandboxOrder> byId(String externalOrderId) {
    return jdbc.sql("SELECT * FROM sandbox_order WHERE external_order_id = :id")
        .param("id", externalOrderId)
        .query(SandboxOrderRepository::map)
        .optional();
  }

  public void updateStatus(String externalOrderId, String status, long chargedMinor, Instant now) {
    jdbc.sql(
            "UPDATE sandbox_order SET status = :status, charged_minor = :charged, updated_at = :now"
                + " WHERE external_order_id = :id")
        .param("status", status)
        .param("charged", chargedMinor)
        .param("now", ts(now))
        .param("id", externalOrderId)
        .update();
  }

  private static SandboxOrder map(ResultSet rs, int rowNum) throws SQLException {
    return new SandboxOrder(
        rs.getString("external_order_id"),
        rs.getString("tenant_id"),
        rs.getString("idempotency_key"),
        rs.getString("provider_offer_id"),
        rs.getString("record_locator"),
        rs.getString("status"),
        rs.getString("currency"),
        rs.getLong("charged_minor"),
        JSON.readValue(
            rs.getString("passengers"),
            JSON.getTypeFactory().constructCollectionType(List.class, String.class)),
        JSON.readValue(
            rs.getString("ticket_numbers"),
            JSON.getTypeFactory().constructCollectionType(List.class, String.class)),
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        rs.getObject("updated_at", OffsetDateTime.class).toInstant());
  }

  private static OffsetDateTime ts(Instant instant) {
    return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }
}
