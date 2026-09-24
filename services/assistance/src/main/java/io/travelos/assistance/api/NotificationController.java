package io.travelos.assistance.api;

import io.travelos.assistance.notify.NotificationRecords.Delivery;
import io.travelos.assistance.notify.NotificationRecords.Notification;
import io.travelos.assistance.notify.NotificationRecords.Preference;
import io.travelos.assistance.notify.NotificationService;
import io.travelos.spring.web.auth.RequestPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Phase 8: a person's inbox and preferences; deliveries for the people who run the platform. */
@RestController
@RequestMapping(path = "/api/v1/notifications", produces = "application/json")
public class NotificationController {
  private final NotificationService notifications;

  public NotificationController(NotificationService notifications) {
    this.notifications = notifications;
  }

  public record NotificationView(
      String notificationId,
      String category,
      String kind,
      String title,
      String body,
      @Nullable String linkKind,
      @Nullable String linkId,
      @Nullable String tripId,
      String priority,
      @Nullable String recipientRole,
      Instant createdAt,
      @Nullable Instant readAt) {
    static NotificationView from(Notification n) {
      return new NotificationView(
          n.notificationId(),
          n.category().name(),
          n.kind(),
          n.title(),
          n.body(),
          n.linkKind(),
          n.linkId(),
          n.tripId(),
          n.priority(),
          n.recipientRole(),
          n.createdAt(),
          n.readAt());
    }
  }

  public record DeliveryView(
      String deliveryId,
      String notificationId,
      String channel,
      @Nullable String address,
      String status,
      int attempts,
      @Nullable String lastError,
      @Nullable String providerRef,
      @Nullable Instant sentAt,
      Instant updatedAt) {
    static DeliveryView from(Delivery d) {
      return new DeliveryView(
          d.deliveryId(),
          d.notificationId(),
          d.channel().name(),
          d.address() == null ? null : masked(d.address()),
          d.status().name(),
          d.attempts(),
          d.lastError(),
          d.providerRef(),
          d.sentAt(),
          d.updatedAt());
    }

    private static String masked(String address) {
      int at = address.indexOf('@');
      return at > 1 ? address.charAt(0) + "***" + address.substring(at) : "***";
    }
  }

  public record PreferenceView(
      String employeeId,
      @Nullable String email,
      @Nullable String chatHandle,
      Map<String, List<String>> channels) {
    static PreferenceView from(Preference p) {
      return new PreferenceView(p.employeeId(), p.email(), p.chatHandle(), p.channels());
    }
  }

  public record PreferenceRequest(
      @Nullable @Size(max = 320) String email,
      @Nullable @Size(max = 200) String chatHandle,
      @Nullable Map<String, List<String>> channels) {}

  @GetMapping
  public List<NotificationView> inbox(
      @AuthenticationPrincipal RequestPrincipal me,
      @RequestParam(defaultValue = "false") boolean unread,
      @RequestParam(defaultValue = "50") int limit) {
    return notifications.inbox(me, unread, limit).stream().map(NotificationView::from).toList();
  }

  @PostMapping("/{notificationId}/read")
  public NotificationView read(
      @AuthenticationPrincipal RequestPrincipal me, @PathVariable String notificationId) {
    return NotificationView.from(notifications.markRead(me, notificationId));
  }

  @PostMapping("/read-all")
  public Map<String, Integer> readAll(@AuthenticationPrincipal RequestPrincipal me) {
    return Map.of("marked", notifications.markAllRead(me));
  }

  @GetMapping("/{notificationId}/deliveries")
  public List<DeliveryView> deliveries(
      @AuthenticationPrincipal RequestPrincipal me, @PathVariable String notificationId) {
    return notifications.deliveries(me, notificationId).stream().map(DeliveryView::from).toList();
  }

  @GetMapping("/deliveries/failed")
  public List<DeliveryView> failed(@AuthenticationPrincipal RequestPrincipal me) {
    return notifications.failedDeliveries(me).stream().map(DeliveryView::from).toList();
  }

  @GetMapping("/preferences")
  public PreferenceView preferences(@AuthenticationPrincipal RequestPrincipal me) {
    return PreferenceView.from(notifications.preference(me));
  }

  @PutMapping(path = "/preferences", consumes = "application/json")
  public PreferenceView update(
      @AuthenticationPrincipal RequestPrincipal me, @Valid @RequestBody PreferenceRequest request) {
    return PreferenceView.from(
        notifications.updatePreference(
            me,
            request.email(),
            request.chatHandle(),
            request.channels() == null ? Map.of() : request.channels()));
  }
}
