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

/** The sandbox hotel and ground suppliers' booking ledger (Slice 3). */
@Repository
public class SandboxBookingRepository {
  private static final JsonMapper JSON = JsonMapper.builder().build();

  public record Booking(
      String bookingId,
      String kind,
      String tenantId,
      String idempotencyKey,
      String providerOfferId,
      String confirmation,
      String status,
      String currency,
      long chargedMinor,
      long penaltyMinor,
      List<String> guests,
      Instant createdAt,
      Instant updatedAt) {}

  public record Change(
      String changeId,
      String tenantId,
      String idempotencyKey,
      String bookingId,
      String previousOfferId,
      String newOfferId,
      long incrementalMinor,
      long chargedMinor,
      Instant createdAt) {}

  private final JdbcClient jdbc;

  public SandboxBookingRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public void insert(Booking b) {
    jdbc.sql(
            """
            INSERT INTO sandbox_booking (booking_id, kind, tenant_id, idempotency_key, provider_offer_id,
              confirmation, status, currency, charged_minor, penalty_minor, guests, created_at, updated_at)
            VALUES (:id, :kind, :tenant, :key, :offer, :confirmation, :status, :currency, :charged, :penalty,
              CAST(:guests AS jsonb), :createdAt, :updatedAt)
            """)
        .param("id", b.bookingId())
        .param("kind", b.kind())
        .param("tenant", b.tenantId())
        .param("key", b.idempotencyKey())
        .param("offer", b.providerOfferId())
        .param("confirmation", b.confirmation())
        .param("status", b.status())
        .param("currency", b.currency())
        .param("charged", b.chargedMinor())
        .param("penalty", b.penaltyMinor())
        .param("guests", JSON.writeValueAsString(b.guests()))
        .param("createdAt", ts(b.createdAt()))
        .param("updatedAt", ts(b.updatedAt()))
        .update();
  }

  public Optional<Booking> byIdempotencyKey(String tenantId, String key) {
    return jdbc.sql(
            "SELECT * FROM sandbox_booking WHERE tenant_id = :tenant AND idempotency_key = :key")
        .param("tenant", tenantId)
        .param("key", key)
        .query(SandboxBookingRepository::map)
        .optional();
  }

  public Optional<Booking> byId(String tenantId, String bookingId) {
    return jdbc.sql("SELECT * FROM sandbox_booking WHERE tenant_id = :tenant AND booking_id = :id")
        .param("tenant", tenantId)
        .param("id", bookingId)
        .query(SandboxBookingRepository::map)
        .optional();
  }

  public void updateStatus(String bookingId, String status, long chargedMinor, Instant now) {
    jdbc.sql(
            "UPDATE sandbox_booking SET status = :status, charged_minor = :charged, updated_at = :now"
                + " WHERE booking_id = :id")
        .param("status", status)
        .param("charged", chargedMinor)
        .param("now", ts(now))
        .param("id", bookingId)
        .update();
  }

  public void reissue(String bookingId, String providerOfferId, long chargedMinor, Instant now) {
    jdbc.sql(
            "UPDATE sandbox_booking SET provider_offer_id = :offer, status = 'CHANGED',"
                + " charged_minor = :charged, updated_at = :now WHERE booking_id = :id")
        .param("offer", providerOfferId)
        .param("charged", chargedMinor)
        .param("now", ts(now))
        .param("id", bookingId)
        .update();
  }

  public void insertChange(Change c) {
    jdbc.sql(
            """
            INSERT INTO sandbox_booking_change (change_id, tenant_id, idempotency_key, booking_id,
              previous_offer_id, new_offer_id, incremental_minor, charged_minor, created_at)
            VALUES (:id, :tenant, :key, :booking, :previous, :next, :incremental, :charged, :at)
            """)
        .param("id", c.changeId())
        .param("tenant", c.tenantId())
        .param("key", c.idempotencyKey())
        .param("booking", c.bookingId())
        .param("previous", c.previousOfferId())
        .param("next", c.newOfferId())
        .param("incremental", c.incrementalMinor())
        .param("charged", c.chargedMinor())
        .param("at", ts(c.createdAt()))
        .update();
  }

  public Optional<Change> changeByIdempotencyKey(String tenantId, String key) {
    return jdbc.sql(
            "SELECT * FROM sandbox_booking_change WHERE tenant_id = :tenant AND idempotency_key = :key")
        .param("tenant", tenantId)
        .param("key", key)
        .query(
            (rs, i) ->
                new Change(
                    rs.getString("change_id"),
                    rs.getString("tenant_id"),
                    rs.getString("idempotency_key"),
                    rs.getString("booking_id"),
                    rs.getString("previous_offer_id"),
                    rs.getString("new_offer_id"),
                    rs.getLong("incremental_minor"),
                    rs.getLong("charged_minor"),
                    rs.getObject("created_at", OffsetDateTime.class).toInstant()))
        .optional();
  }

  public long count(String kind, String tenantId) {
    return jdbc.sql(
            "SELECT count(*) FROM sandbox_booking WHERE kind = :kind AND tenant_id = :tenant")
        .param("kind", kind)
        .param("tenant", tenantId)
        .query(Long.class)
        .single();
  }

  private static Booking map(ResultSet rs, int rowNum) throws SQLException {
    return new Booking(
        rs.getString("booking_id"),
        rs.getString("kind"),
        rs.getString("tenant_id"),
        rs.getString("idempotency_key"),
        rs.getString("provider_offer_id"),
        rs.getString("confirmation"),
        rs.getString("status"),
        rs.getString("currency"),
        rs.getLong("charged_minor"),
        rs.getLong("penalty_minor"),
        JSON.readValue(
            rs.getString("guests"),
            JSON.getTypeFactory().constructCollectionType(List.class, String.class)),
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        rs.getObject("updated_at", OffsetDateTime.class).toInstant());
  }

  private static OffsetDateTime ts(Instant instant) {
    return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }
}
