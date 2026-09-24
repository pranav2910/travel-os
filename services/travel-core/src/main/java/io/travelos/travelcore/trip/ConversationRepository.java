package io.travelos.travelcore.trip;

import io.travelos.common.identity.Principal;
import io.travelos.common.tenant.TenantId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ConversationRepository {
  private final JdbcClient jdbc;

  public ConversationRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public int insert(Conversation c) {
    return jdbc.sql(
            """
            INSERT INTO conversation (conversation_id, tenant_id, traveler_id, created_by, status, current_trip_id,
              idempotency_key, created_at, updated_at)
            VALUES (:id, :t, :traveler, :by, :status, :trip, :key, :created, :updated)
            ON CONFLICT ON CONSTRAINT conversation_idempotency DO NOTHING
            """)
        .param("id", c.conversationId())
        .param("t", c.tenant().value())
        .param("traveler", c.travelerId())
        .param("by", c.createdBy().id())
        .param("status", c.status().name())
        .param("trip", c.currentTripId())
        .param("key", c.idempotencyKey())
        .param("created", Timestamp.from(c.createdAt()))
        .param("updated", Timestamp.from(c.updatedAt()))
        .update();
  }

  public Optional<Conversation> find(TenantId tenant, String conversationId) {
    return jdbc.sql("SELECT * FROM conversation WHERE tenant_id = :t AND conversation_id = :id")
        .param("t", tenant.value())
        .param("id", conversationId)
        .query(ConversationRepository::map)
        .optional();
  }

  public Optional<Conversation> findByIdempotencyKey(TenantId tenant, String key) {
    return jdbc.sql("SELECT * FROM conversation WHERE tenant_id = :t AND idempotency_key = :key")
        .param("t", tenant.value())
        .param("key", key)
        .query(ConversationRepository::map)
        .optional();
  }

  public Optional<Conversation> findByTrip(TenantId tenant, String tripId) {
    return jdbc.sql(
            """
            SELECT c.* FROM conversation c JOIN conversation_message m ON m.conversation_id = c.conversation_id
            WHERE c.tenant_id = :t AND m.trip_id = :trip LIMIT 1
            """)
        .param("t", tenant.value())
        .param("trip", tripId)
        .query(ConversationRepository::map)
        .optional();
  }

  public List<Conversation> listForTraveler(TenantId tenant, String travelerId, int limit) {
    return jdbc.sql(
            "SELECT * FROM conversation WHERE tenant_id = :t AND traveler_id = :tr ORDER BY created_at DESC LIMIT :n")
        .param("t", tenant.value())
        .param("tr", travelerId)
        .param("n", limit)
        .query(ConversationRepository::map)
        .list();
  }

  public void update(
      TenantId tenant,
      String conversationId,
      Conversation.Status status,
      @Nullable String currentTripId,
      Instant now) {
    jdbc.sql(
            "UPDATE conversation SET status = :status, current_trip_id = COALESCE(:trip, current_trip_id), updated_at = :now WHERE tenant_id = :t AND conversation_id = :id")
        .param("status", status.name())
        .param("trip", currentTripId)
        .param("now", Timestamp.from(now))
        .param("t", tenant.value())
        .param("id", conversationId)
        .update();
  }

  public Conversation.Message append(
      TenantId tenant,
      String conversationId,
      String messageId,
      Conversation.Role role,
      String text,
      @Nullable String tripId,
      @Nullable String kind,
      @Nullable String idempotencyKey,
      Instant now) {
    Integer next =
        jdbc.sql(
                "SELECT COALESCE(MAX(seq), 0) + 1 FROM conversation_message WHERE conversation_id = :id")
            .param("id", conversationId)
            .query(Integer.class)
            .single();
    int seq = next == null ? 1 : next;
    jdbc.sql(
            """
            INSERT INTO conversation_message (message_id, tenant_id, conversation_id, seq, role, text, trip_id, kind,
              idempotency_key, created_at)
            VALUES (:id, :t, :c, :seq, :role, :text, :trip, :kind, :key, :now)
            """)
        .param("key", idempotencyKey)
        .param("id", messageId)
        .param("t", tenant.value())
        .param("c", conversationId)
        .param("seq", seq)
        .param("role", role.name())
        .param("text", text)
        .param("trip", tripId)
        .param("kind", kind)
        .param("now", Timestamp.from(now))
        .update();
    return new Conversation.Message(
        messageId, conversationId, seq, role, text, tripId, kind, idempotencyKey, now);
  }

  public List<Conversation.Message> messages(TenantId tenant, String conversationId) {
    return jdbc.sql(
            "SELECT * FROM conversation_message WHERE tenant_id = :t AND conversation_id = :id ORDER BY seq")
        .param("t", tenant.value())
        .param("id", conversationId)
        .query(
            (rs, i) ->
                new Conversation.Message(
                    rs.getString("message_id"),
                    rs.getString("conversation_id"),
                    rs.getInt("seq"),
                    Conversation.Role.valueOf(rs.getString("role")),
                    rs.getString("text"),
                    rs.getString("trip_id"),
                    rs.getString("kind"),
                    rs.getString("idempotency_key"),
                    rs.getObject("created_at", OffsetDateTime.class).toInstant()))
        .list();
  }

  private static Conversation map(ResultSet rs, int i) throws SQLException {
    return new Conversation(
        rs.getString("conversation_id"),
        TenantId.of(rs.getString("tenant_id")),
        rs.getString("traveler_id"),
        Principal.parse(rs.getString("created_by")),
        Conversation.Status.valueOf(rs.getString("status")),
        rs.getString("current_trip_id"),
        rs.getString("idempotency_key"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        rs.getObject("updated_at", OffsetDateTime.class).toInstant());
  }
}
