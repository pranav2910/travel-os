package io.travelos.assistance.notify;

import io.travelos.assistance.notify.NotificationRecords.Category;
import io.travelos.assistance.notify.NotificationRecords.Channel;
import io.travelos.assistance.notify.NotificationRecords.Delivery;
import io.travelos.assistance.notify.NotificationRecords.DeliveryStatus;
import io.travelos.assistance.notify.NotificationRecords.Notification;
import io.travelos.assistance.notify.NotificationRecords.Preference;
import io.travelos.common.tenant.TenantId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class NotificationRepository {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final TypeReference<Map<String, List<String>>> CHANNELS = new TypeReference<>() {};
  private final JdbcClient jdbc;

  public NotificationRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** Inserts unless the same fact already reached the same recipient (returns false then). */
  public boolean insert(Notification n) {
    return jdbc.sql(
                """
                INSERT INTO notification (notification_id, tenant_id, recipient_employee_id, recipient_role, category, kind, title, body,
                  link_kind, link_id, trip_id, dedupe_key, priority, created_at, source_event_id)
                VALUES (:id, :tenant, :employee, :role, :category, :kind, :title, :body, :linkKind, :linkId, :trip, :key, :priority, :at, :src)
                ON CONFLICT (tenant_id, dedupe_key) DO NOTHING
                """)
            .param("id", n.notificationId())
            .param("tenant", n.tenant().value())
            .param("employee", n.recipientEmployeeId())
            .param("role", n.recipientRole())
            .param("category", n.category().name())
            .param("kind", n.kind())
            .param("title", n.title())
            .param("body", n.body())
            .param("linkKind", n.linkKind())
            .param("linkId", n.linkId())
            .param("trip", n.tripId())
            .param("key", n.dedupeKey())
            .param("priority", n.priority())
            .param("at", Timestamp.from(n.createdAt()))
            .param("src", n.sourceEventId())
            .update()
        == 1;
  }

  public Optional<Notification> find(TenantId tenant, String id) {
    return jdbc.sql("SELECT * FROM notification WHERE tenant_id = :t AND notification_id = :id")
        .param("t", tenant.value())
        .param("id", id)
        .query(NotificationRepository::notification)
        .optional();
  }

  /** A person's inbox: what was addressed to them, plus what was addressed to a role they hold. */
  public List<Notification> inbox(
      TenantId tenant, String employeeId, Set<String> roles, boolean unreadOnly, int limit) {
    List<String> roleList = roles.isEmpty() ? List.of("-") : List.copyOf(roles);
    return jdbc.sql(
            "SELECT * FROM notification WHERE tenant_id = :t AND (recipient_employee_id = :e OR recipient_role IN (:roles))"
                + (unreadOnly ? " AND read_at IS NULL" : "")
                + " ORDER BY created_at DESC LIMIT :n")
        .param("t", tenant.value())
        .param("e", employeeId)
        .param("roles", roleList)
        .param("n", limit)
        .query(NotificationRepository::notification)
        .list();
  }

  public int markRead(
      TenantId tenant, String employeeId, Set<String> roles, @Nullable String id, Instant now) {
    List<String> roleList = roles.isEmpty() ? List.of("-") : List.copyOf(roles);
    return jdbc.sql(
            "UPDATE notification SET read_at = :now WHERE tenant_id = :t AND read_at IS NULL"
                + " AND (recipient_employee_id = :e OR recipient_role IN (:roles))"
                + (id == null ? "" : " AND notification_id = :id"))
        .param("now", Timestamp.from(now))
        .param("t", tenant.value())
        .param("e", employeeId)
        .param("roles", roleList)
        .param("id", id)
        .update();
  }

  // ------------------------------------------------------------------ deliveries

  public void insertDelivery(Delivery d) {
    jdbc.sql(
            """
            INSERT INTO notification_delivery (delivery_id, notification_id, tenant_id, channel, address, status, attempts,
              next_attempt_at, last_error, provider_ref, sent_at, created_at, updated_at)
            VALUES (:id, :n, :tenant, :channel, :address, :status, :attempts, :next, :error, :ref, :sent, :at, :at)
            """)
        .param("id", d.deliveryId())
        .param("n", d.notificationId())
        .param("tenant", d.tenant().value())
        .param("channel", d.channel().name())
        .param("address", d.address())
        .param("status", d.status().name())
        .param("attempts", d.attempts())
        .param("next", d.nextAttemptAt() == null ? null : Timestamp.from(d.nextAttemptAt()))
        .param("error", d.lastError())
        .param("ref", d.providerRef())
        .param("sent", d.sentAt() == null ? null : Timestamp.from(d.sentAt()))
        .param("at", Timestamp.from(d.createdAt()))
        .update();
  }

  public void updateDelivery(
      String deliveryId,
      DeliveryStatus status,
      int attempts,
      @Nullable Instant nextAttemptAt,
      @Nullable String error,
      @Nullable String providerRef,
      @Nullable Instant sentAt,
      Instant now) {
    jdbc.sql(
            "UPDATE notification_delivery SET status = :s, attempts = :a, next_attempt_at = :next, last_error = :e, provider_ref = :ref,"
                + " sent_at = :sent, updated_at = :now WHERE delivery_id = :id")
        .param("s", status.name())
        .param("a", attempts)
        .param("next", nextAttemptAt == null ? null : Timestamp.from(nextAttemptAt))
        .param("e", error == null ? null : error.length() > 500 ? error.substring(0, 500) : error)
        .param("ref", providerRef)
        .param("sent", sentAt == null ? null : Timestamp.from(sentAt))
        .param("now", Timestamp.from(now))
        .param("id", deliveryId)
        .update();
  }

  public List<Delivery> due(Instant now, int limit) {
    return jdbc.sql(
            "SELECT * FROM notification_delivery WHERE status = 'PENDING' AND next_attempt_at <= :now ORDER BY next_attempt_at LIMIT :n FOR UPDATE SKIP LOCKED")
        .param("now", Timestamp.from(now))
        .param("n", limit)
        .query(NotificationRepository::delivery)
        .list();
  }

  public List<Delivery> deliveriesOf(TenantId tenant, String notificationId) {
    return jdbc.sql(
            "SELECT * FROM notification_delivery WHERE tenant_id = :t AND notification_id = :n ORDER BY created_at")
        .param("t", tenant.value())
        .param("n", notificationId)
        .query(NotificationRepository::delivery)
        .list();
  }

  public List<Delivery> deliveriesByStatus(TenantId tenant, DeliveryStatus status, int limit) {
    return jdbc.sql(
            "SELECT * FROM notification_delivery WHERE tenant_id = :t AND status = :s ORDER BY updated_at DESC LIMIT :n")
        .param("t", tenant.value())
        .param("s", status.name())
        .param("n", limit)
        .query(NotificationRepository::delivery)
        .list();
  }

  // ------------------------------------------------------------------ preferences

  public Optional<Preference> preference(TenantId tenant, String employeeId) {
    return jdbc.sql(
            "SELECT * FROM notification_preference WHERE tenant_id = :t AND employee_id = :e")
        .param("t", tenant.value())
        .param("e", employeeId)
        .query(NotificationRepository::preference)
        .optional();
  }

  public void upsertPreference(Preference p) {
    jdbc.sql(
            """
            INSERT INTO notification_preference (tenant_id, employee_id, email, chat_handle, channels, updated_at)
            VALUES (:t, :e, :email, :chat, CAST(:channels AS jsonb), :now)
            ON CONFLICT (tenant_id, employee_id) DO UPDATE SET email = COALESCE(EXCLUDED.email, notification_preference.email),
              chat_handle = COALESCE(EXCLUDED.chat_handle, notification_preference.chat_handle), channels = EXCLUDED.channels, updated_at = EXCLUDED.updated_at
            """)
        .param("t", p.tenant().value())
        .param("e", p.employeeId())
        .param("email", p.email())
        .param("chat", p.chatHandle())
        .param("channels", JSON.writeValueAsString(p.channels()))
        .param("now", Timestamp.from(p.updatedAt()))
        .update();
  }

  /** Learns an address without touching the person's chosen channels. */
  public void rememberEmail(TenantId tenant, String employeeId, String email, Instant now) {
    jdbc.sql(
            """
            INSERT INTO notification_preference (tenant_id, employee_id, email, channels, updated_at)
            VALUES (:t, :e, :email, '{}'::jsonb, :now)
            ON CONFLICT (tenant_id, employee_id) DO UPDATE SET email = COALESCE(notification_preference.email, EXCLUDED.email)
            """)
        .param("t", tenant.value())
        .param("e", employeeId)
        .param("email", email)
        .param("now", Timestamp.from(now))
        .update();
  }

  // ------------------------------------------------------------------ mappers

  private static Notification notification(ResultSet rs, int i) throws SQLException {
    return new Notification(
        rs.getString("notification_id"),
        TenantId.of(rs.getString("tenant_id")),
        rs.getString("recipient_employee_id"),
        rs.getString("recipient_role"),
        Category.valueOf(rs.getString("category")),
        rs.getString("kind"),
        rs.getString("title"),
        rs.getString("body"),
        rs.getString("link_kind"),
        rs.getString("link_id"),
        rs.getString("trip_id"),
        rs.getString("dedupe_key"),
        rs.getString("priority"),
        instant(rs, "created_at"),
        optional(rs, "read_at"),
        rs.getString("source_event_id"));
  }

  private static Delivery delivery(ResultSet rs, int i) throws SQLException {
    return new Delivery(
        rs.getString("delivery_id"),
        rs.getString("notification_id"),
        TenantId.of(rs.getString("tenant_id")),
        Channel.valueOf(rs.getString("channel")),
        rs.getString("address"),
        DeliveryStatus.valueOf(rs.getString("status")),
        rs.getInt("attempts"),
        optional(rs, "next_attempt_at"),
        rs.getString("last_error"),
        rs.getString("provider_ref"),
        optional(rs, "sent_at"),
        instant(rs, "created_at"),
        instant(rs, "updated_at"));
  }

  private static Preference preference(ResultSet rs, int i) throws SQLException {
    String channels = rs.getString("channels");
    return new Preference(
        TenantId.of(rs.getString("tenant_id")),
        rs.getString("employee_id"),
        rs.getString("email"),
        rs.getString("chat_handle"),
        channels == null ? Map.of() : JSON.readValue(channels, CHANNELS),
        instant(rs, "updated_at"));
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    return rs.getObject(column, OffsetDateTime.class).toInstant();
  }

  private static @Nullable Instant optional(ResultSet rs, String column) throws SQLException {
    OffsetDateTime v = rs.getObject(column, OffsetDateTime.class);
    return v == null ? null : v.toInstant();
  }
}
