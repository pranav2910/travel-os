package io.travelos.assistance.notify;

import io.travelos.common.tenant.TenantId;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Phase 8: notification records. */
public final class NotificationRecords {
  private NotificationRecords() {}

  public enum Category {
    TRIP,
    APPROVAL,
    DISRUPTION,
    CASE,
    SAFETY,
    FINANCE
  }

  public enum Channel {
    IN_APP,
    EMAIL,
    CHAT
  }

  public enum DeliveryStatus {
    PENDING,
    SENT,
    FAILED,
    SKIPPED
  }

  public record Notification(
      String notificationId,
      TenantId tenant,
      @Nullable String recipientEmployeeId,
      @Nullable String recipientRole,
      Category category,
      String kind,
      String title,
      String body,
      @Nullable String linkKind,
      @Nullable String linkId,
      @Nullable String tripId,
      String dedupeKey,
      String priority,
      Instant createdAt,
      @Nullable Instant readAt,
      @Nullable String sourceEventId) {}

  public record Delivery(
      String deliveryId,
      String notificationId,
      TenantId tenant,
      Channel channel,
      @Nullable String address,
      DeliveryStatus status,
      int attempts,
      @Nullable Instant nextAttemptAt,
      @Nullable String lastError,
      @Nullable String providerRef,
      @Nullable Instant sentAt,
      Instant createdAt,
      Instant updatedAt) {}

  /**
   * @param channels per category, the channels wanted; a category absent here gets the defaults
   *     (IN_APP and EMAIL; SAFETY adds CHAT when a handle exists)
   */
  public record Preference(
      TenantId tenant,
      String employeeId,
      @Nullable String email,
      @Nullable String chatHandle,
      Map<String, List<String>> channels,
      Instant updatedAt) {
    public Preference {
      channels = channels == null ? Map.of() : Map.copyOf(channels);
    }

    public List<Channel> channelsFor(Category category) {
      List<String> wanted = channels.get(category.name());
      if (wanted == null) {
        return category == Category.SAFETY
            ? List.of(Channel.IN_APP, Channel.EMAIL, Channel.CHAT)
            : List.of(Channel.IN_APP, Channel.EMAIL);
      }
      return wanted.stream().map(Channel::valueOf).toList();
    }
  }
}
