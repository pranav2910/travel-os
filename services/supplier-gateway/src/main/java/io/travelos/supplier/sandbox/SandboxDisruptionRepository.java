package io.travelos.supplier.sandbox;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/** The sandbox airline's memory of cancellations and reissues. */
@Repository
public class SandboxDisruptionRepository {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  public record Change(
      String changeId,
      String tenantId,
      String idempotencyKey,
      String externalOrderId,
      String previousOfferId,
      String newOfferId,
      long incrementalMinor,
      long chargedMinor,
      List<String> ticketNumbers,
      Instant createdAt) {}

  private final JdbcClient jdbc;

  public SandboxDisruptionRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  void saveReaccommodation(SandboxReaccommodation r, Instant now) {
    jdbc.sql(
            """
            INSERT INTO sandbox_reaccommodation (tenant_id, correlation_id, origin, destination, outbound_date, inbound_date, cabin,
              cancelled_slot, cancelled_flight, replacement_slot, original_fare_minor, delta_minor, next_day, created_at)
            VALUES (:tenant, :correlation, :origin, :destination, :out, :in, :cabin, :cancelled, :flight, :replacement, :fare, :delta, :nextDay, :now)
            ON CONFLICT (tenant_id, correlation_id, origin, destination, outbound_date, cabin) DO UPDATE SET
              inbound_date = EXCLUDED.inbound_date, cancelled_slot = EXCLUDED.cancelled_slot, cancelled_flight = EXCLUDED.cancelled_flight,
              replacement_slot = EXCLUDED.replacement_slot, original_fare_minor = EXCLUDED.original_fare_minor,
              delta_minor = EXCLUDED.delta_minor, next_day = EXCLUDED.next_day, created_at = EXCLUDED.created_at
            """)
        .param("tenant", r.tenantId())
        .param("correlation", r.correlationId())
        .param("origin", r.origin())
        .param("destination", r.destination())
        .param("out", Date.valueOf(r.outboundDate()))
        .param("in", r.inboundDate() == null ? null : Date.valueOf(r.inboundDate()))
        .param("cabin", r.cabin())
        .param("cancelled", r.cancelledSlot())
        .param("flight", r.cancelledFlight())
        .param("replacement", r.replacementSlot())
        .param("fare", r.originalFareMinor())
        .param("delta", r.deltaMinor())
        .param("nextDay", r.nextDay())
        .param("now", Timestamp.from(now))
        .update();
  }

  /**
   * The most recent cancellation on this route and day for the tenant, whatever cabin was booked:
   * the flight is gone in every cabin; the fares it carries apply to that cabin and that trip.
   */
  Optional<SandboxReaccommodation> reaccommodation(
      String tenantId, String origin, String destination, LocalDate outboundDate) {
    return jdbc.sql(
            """
            SELECT * FROM sandbox_reaccommodation
            WHERE tenant_id = :tenant AND origin = :origin AND destination = :destination
              AND outbound_date = :out
            ORDER BY created_at DESC LIMIT 1
            """)
        .param("tenant", tenantId)
        .param("origin", origin)
        .param("destination", destination)
        .param("out", Date.valueOf(outboundDate))
        .query(
            (rs, i) -> {
              Date in = rs.getDate("inbound_date");
              return new SandboxReaccommodation(
                  rs.getString("tenant_id"),
                  rs.getString("correlation_id"),
                  rs.getString("origin"),
                  rs.getString("destination"),
                  rs.getDate("outbound_date").toLocalDate(),
                  in == null ? null : in.toLocalDate(),
                  rs.getString("cabin"),
                  rs.getInt("cancelled_slot"),
                  rs.getString("cancelled_flight"),
                  rs.getInt("replacement_slot"),
                  rs.getLong("original_fare_minor"),
                  rs.getLong("delta_minor"),
                  rs.getBoolean("next_day"));
            })
        .optional();
  }

  void insertChange(Change c) {
    jdbc.sql(
            """
            INSERT INTO sandbox_order_change (change_id, tenant_id, idempotency_key, external_order_id, previous_offer_id,
              new_offer_id, incremental_minor, charged_minor, ticket_numbers, created_at)
            VALUES (:id, :tenant, :key, :order, :prev, :next, :incremental, :charged, CAST(:tickets AS jsonb), :now)
            """)
        .param("id", c.changeId())
        .param("tenant", c.tenantId())
        .param("key", c.idempotencyKey())
        .param("order", c.externalOrderId())
        .param("prev", c.previousOfferId())
        .param("next", c.newOfferId())
        .param("incremental", c.incrementalMinor())
        .param("charged", c.chargedMinor())
        .param("tickets", JSON.writeValueAsString(c.ticketNumbers()))
        .param("now", Timestamp.from(c.createdAt()))
        .update();
  }

  Optional<Change> changeByIdempotencyKey(String tenantId, String key) {
    return jdbc.sql(
            "SELECT * FROM sandbox_order_change WHERE tenant_id = :tenant AND idempotency_key = :key")
        .param("tenant", tenantId)
        .param("key", key)
        .query(SandboxDisruptionRepository::mapChange)
        .optional();
  }

  public long changesFor(String externalOrderId) {
    Long n =
        jdbc.sql("SELECT count(*) FROM sandbox_order_change WHERE external_order_id = :id")
            .param("id", externalOrderId)
            .query(Long.class)
            .single();
    return n == null ? 0 : n;
  }

  private static Change mapChange(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
    return new Change(
        rs.getString("change_id"),
        rs.getString("tenant_id"),
        rs.getString("idempotency_key"),
        rs.getString("external_order_id"),
        rs.getString("previous_offer_id"),
        rs.getString("new_offer_id"),
        rs.getLong("incremental_minor"),
        rs.getLong("charged_minor"),
        JSON.readValue(
            rs.getString("ticket_numbers"),
            JSON.getTypeFactory().constructCollectionType(List.class, String.class)),
        rs.getTimestamp("created_at").toInstant());
  }

  @Nullable
  static LocalDate date(@Nullable Date d) {
    return d == null ? null : d.toLocalDate();
  }
}
