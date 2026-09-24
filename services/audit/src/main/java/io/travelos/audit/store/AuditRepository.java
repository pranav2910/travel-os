package io.travelos.audit.store;

import io.travelos.common.tenant.TenantId;
import io.travelos.events.EventEnvelope;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class AuditRepository {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {};
  private static final String COLUMNS =
      "event_id, event_type, event_version, occurred_at, received_at, tenant_id, correlation_id,"
          + " causation_id, producer, data::text AS data";

  private final JdbcClient jdbc;

  public AuditRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** Inserts, or returns false when this event id was already stored (Kafka redelivery). */
  public boolean insertIfAbsent(
      EventEnvelope event, String topic, int partition, long offset, Instant receivedAt) {
    int rows =
        jdbc.sql(
                """
                INSERT INTO audit_event (event_id, event_type, event_version, occurred_at, received_at,
                  tenant_id, correlation_id, causation_id, producer, topic, partition, kafka_offset, data)
                VALUES (:eventId, :eventType, :eventVersion, :occurredAt, :receivedAt, :tenantId,
                  :correlationId, :causationId, :producer, :topic, :partition, :offset, CAST(:data AS jsonb))
                ON CONFLICT (event_id) DO NOTHING
                """)
            .param("eventId", event.eventId())
            .param("eventType", event.eventType())
            .param("eventVersion", event.eventVersion())
            .param("occurredAt", ts(event.occurredAt()))
            .param("receivedAt", ts(receivedAt))
            .param("tenantId", event.tenantId())
            .param("correlationId", event.correlationId())
            .param("causationId", event.causationId())
            .param("producer", event.producer())
            .param("topic", topic)
            .param("partition", partition)
            .param("offset", offset)
            .param("data", JSON.writeValueAsString(event.data()))
            .update();
    return rows == 1;
  }

  public void quarantine(
      String topic, int partition, long offset, Instant receivedAt, String reason, String payload) {
    jdbc.sql(
            """
            INSERT INTO audit_quarantine (topic, partition, kafka_offset, received_at, reason, payload)
            VALUES (:topic, :partition, :offset, :receivedAt, :reason, :payload)
            """)
        .param("topic", topic)
        .param("partition", partition)
        .param("offset", offset)
        .param("receivedAt", ts(receivedAt))
        .param("reason", reason)
        .param("payload", payload)
        .update();
  }

  public void indexTrip(TenantId tenant, String tripId, String travelerId, Instant createdAt) {
    jdbc.sql(
            """
            INSERT INTO trip_index (tenant_id, trip_id, traveler_id, created_at)
            VALUES (:tenantId, :tripId, :travelerId, :createdAt)
            ON CONFLICT (tenant_id, trip_id) DO NOTHING
            """)
        .param("tenantId", tenant.value())
        .param("tripId", tripId)
        .param("travelerId", travelerId)
        .param("createdAt", ts(createdAt))
        .update();
  }

  public Optional<TripIndexEntry> findTrip(TenantId tenant, String tripId) {
    return jdbc.sql(
            "SELECT tenant_id, trip_id, traveler_id, created_at FROM trip_index"
                + " WHERE tenant_id = :tenantId AND trip_id = :tripId")
        .param("tenantId", tenant.value())
        .param("tripId", tripId)
        .query(
            (rs, n) ->
                new TripIndexEntry(
                    rs.getString("tenant_id"),
                    rs.getString("trip_id"),
                    rs.getString("traveler_id"),
                    instant(rs, "created_at")))
        .optional();
  }

  /** Everything that happened to one correlation id, in the order it happened. */
  public List<AuditRecord> trail(TenantId tenant, String correlationId) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM audit_event WHERE tenant_id = :tenantId AND correlation_id = :correlationId"
                + " ORDER BY occurred_at, event_id")
        .param("tenantId", tenant.value())
        .param("correlationId", correlationId)
        .query(AuditRepository::map)
        .list();
  }

  public List<AuditRecord> byType(
      TenantId tenant, String eventType, @Nullable Instant from, @Nullable Instant to, int limit) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM audit_event WHERE tenant_id = :tenantId AND event_type = :eventType"
                + " AND (CAST(:from AS timestamptz) IS NULL OR occurred_at >= :from)"
                + " AND (CAST(:to AS timestamptz) IS NULL OR occurred_at < :to)"
                + " ORDER BY occurred_at DESC, event_id DESC LIMIT :limit")
        .param("tenantId", tenant.value())
        .param("eventType", eventType)
        .param("from", ts(from))
        .param("to", ts(to))
        .param("limit", limit)
        .query(AuditRepository::map)
        .list();
  }

  /** Phase 9: the stored data column as a map, for the reports. */
  public static Map<String, Object> data(String json) {
    return JSON.readValue(json, OBJECT);
  }

  public long quarantined() {
    Long n = jdbc.sql("SELECT count(*) FROM audit_quarantine").query(Long.class).single();
    return n == null ? 0 : n;
  }

  private static AuditRecord map(ResultSet rs, int rowNum) throws SQLException {
    return new AuditRecord(
        rs.getString("event_id"),
        rs.getString("event_type"),
        rs.getInt("event_version"),
        instant(rs, "occurred_at"),
        instant(rs, "received_at"),
        rs.getString("tenant_id"),
        rs.getString("correlation_id"),
        rs.getString("causation_id"),
        rs.getString("producer"),
        JSON.readValue(rs.getString("data"), OBJECT));
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    return rs.getObject(column, OffsetDateTime.class).toInstant();
  }

  private static @Nullable OffsetDateTime ts(@Nullable Instant instant) {
    return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }
}
