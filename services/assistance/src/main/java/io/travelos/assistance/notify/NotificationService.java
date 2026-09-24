package io.travelos.assistance.notify;

import io.travelos.assistance.AssistanceProperties;
import io.travelos.assistance.events.AssistanceEvents;
import io.travelos.assistance.metrics.AssistanceMetrics;
import io.travelos.assistance.notify.NotificationRecords.Category;
import io.travelos.assistance.notify.NotificationRecords.Channel;
import io.travelos.assistance.notify.NotificationRecords.Delivery;
import io.travelos.assistance.notify.NotificationRecords.DeliveryStatus;
import io.travelos.assistance.notify.NotificationRecords.Notification;
import io.travelos.assistance.notify.NotificationRecords.Preference;
import io.travelos.common.ids.IdPrefix;
import io.travelos.common.ids.Ids;
import io.travelos.common.tenant.TenantId;
import io.travelos.spring.outbox.Outbox;
import io.travelos.spring.web.auth.RequestPrincipal;
import io.travelos.spring.web.error.ApiException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase 8: durable notifications. A notification is a row before it is anything else; each channel
 * it goes out on is a delivery the dispatcher retries. In-app is always on; email needs an address
 * (learned from the person's sign-in claims, an event, or their preferences) and a provider; chat
 * needs a handle and a webhook. The same fact reaches a recipient once.
 */
@Service
public class NotificationService {
  private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
  static final String SYSTEM = "service/assistance";

  public record Request(
      TenantId tenant,
      @Nullable String recipientEmployeeId,
      @Nullable String recipientRole,
      @Nullable String recipientEmail,
      Category category,
      String kind,
      String title,
      String body,
      @Nullable String linkKind,
      @Nullable String linkId,
      @Nullable String tripId,
      String dedupeKey,
      String priority,
      @Nullable String sourceEventId) {}

  private final NotificationRepository store;
  private final Map<Channel, NotificationChannel> channels = new LinkedHashMap<>();
  private final Outbox outbox;
  private final AssistanceMetrics metrics;
  private final AssistanceProperties properties;
  private final Clock clock;

  public NotificationService(
      NotificationRepository store,
      List<NotificationChannel> channels,
      Outbox outbox,
      AssistanceMetrics metrics,
      AssistanceProperties properties,
      Clock clock) {
    this.store = store;
    channels.forEach(c -> this.channels.put(c.channel(), c));
    this.outbox = outbox;
    this.metrics = metrics;
    this.properties = properties;
    this.clock = clock;
  }

  /** Records the notification and its deliveries; a repeat of the same fact does nothing. */
  @Transactional
  public Optional<Notification> notify(Request r) {
    Instant now = clock.instant();
    Notification n =
        new Notification(
            Ids.newId(IdPrefix.NOTIFICATION),
            r.tenant(),
            r.recipientEmployeeId(),
            r.recipientRole(),
            r.category(),
            r.kind(),
            r.title(),
            r.body(),
            r.linkKind(),
            r.linkId(),
            r.tripId(),
            r.dedupeKey(),
            r.priority(),
            now,
            null,
            r.sourceEventId());
    if (!store.insert(n)) {
      return Optional.empty();
    }
    // in-app always
    store.insertDelivery(delivery(n, Channel.IN_APP, null, DeliveryStatus.SENT, now, now));
    metrics.notification(n.category().name(), "IN_APP", "SENT");
    if (r.recipientEmployeeId() != null) {
      Preference p = store.preference(r.tenant(), r.recipientEmployeeId()).orElse(null);
      if (p == null && r.recipientEmail() != null) {
        store.rememberEmail(r.tenant(), r.recipientEmployeeId(), r.recipientEmail(), now);
      }
      List<Channel> wanted = p == null ? defaults(r.category()) : p.channelsFor(r.category());
      String email = p != null && p.email() != null ? p.email() : r.recipientEmail();
      String chat = p == null ? null : p.chatHandle();
      for (Channel c : wanted) {
        if (c == Channel.IN_APP) {
          continue;
        }
        String address = c == Channel.EMAIL ? email : chat;
        boolean possible = address != null && !address.isBlank() && channels.containsKey(c);
        store.insertDelivery(
            delivery(
                n,
                c,
                address,
                possible ? DeliveryStatus.PENDING : DeliveryStatus.SKIPPED,
                now,
                now));
        if (!possible) {
          metrics.notification(n.category().name(), c.name(), "SKIPPED");
        }
      }
    }
    return Optional.of(n);
  }

  private static List<Channel> defaults(Category category) {
    return category == Category.SAFETY
        ? List.of(Channel.IN_APP, Channel.EMAIL, Channel.CHAT)
        : List.of(Channel.IN_APP, Channel.EMAIL);
  }

  private static Delivery delivery(
      Notification n,
      Channel c,
      @Nullable String address,
      DeliveryStatus status,
      Instant now,
      Instant next) {
    return new Delivery(
        Ids.newId(IdPrefix.NOTIFICATION_DELIVERY),
        n.notificationId(),
        n.tenant(),
        c,
        address,
        status,
        0,
        status == DeliveryStatus.PENDING ? next : null,
        null,
        null,
        status == DeliveryStatus.SENT ? now : null,
        now,
        now);
  }

  /** One dispatcher pass: every due delivery tried once, with backoff on failure. */
  @Transactional
  public int dispatch() {
    Instant now = clock.instant();
    int moved = 0;
    for (Delivery d : store.due(now, 100)) {
      Notification n = store.find(d.tenant(), d.notificationId()).orElse(null);
      NotificationChannel channel = channels.get(d.channel());
      if (n == null || channel == null || d.address() == null) {
        store.updateDelivery(
            d.deliveryId(),
            DeliveryStatus.SKIPPED,
            d.attempts(),
            null,
            "no channel or address",
            null,
            null,
            now);
        continue;
      }
      int attempts = d.attempts() + 1;
      try {
        String ref = channel.send(n, d.address());
        store.updateDelivery(
            d.deliveryId(), DeliveryStatus.SENT, attempts, null, null, ref, now, now);
        outbox.append(AssistanceEvents.notificationSent(n, d.channel().name(), "SENT", ref, clock));
        metrics.notification(n.category().name(), d.channel().name(), "SENT");
      } catch (NotificationChannel.ChannelException e) {
        boolean giveUp = !e.retryable() || attempts >= properties.notifications().maxAttempts();
        if (giveUp) {
          store.updateDelivery(
              d.deliveryId(),
              DeliveryStatus.FAILED,
              attempts,
              null,
              e.getMessage(),
              null,
              null,
              now);
          outbox.append(
              AssistanceEvents.notificationSent(n, d.channel().name(), "FAILED", null, clock));
          metrics.notification(n.category().name(), d.channel().name(), "FAILED");
          log.warn(
              "notification {} on {} failed for good: {}",
              n.notificationId(),
              d.channel(),
              e.getMessage());
        } else {
          Duration backoff =
              Duration.ofSeconds((long) Math.min(600, 5 * Math.pow(3, attempts - 1)));
          store.updateDelivery(
              d.deliveryId(),
              DeliveryStatus.PENDING,
              attempts,
              now.plus(backoff),
              e.getMessage(),
              null,
              null,
              now);
        }
      } catch (RuntimeException e) {
        store.updateDelivery(
            d.deliveryId(), DeliveryStatus.FAILED, attempts, null, e.getMessage(), null, null, now);
        metrics.notification(n.category().name(), d.channel().name(), "FAILED");
        log.error("notification {} on {}: {}", n.notificationId(), d.channel(), e.toString());
      }
      moved++;
    }
    return moved;
  }

  // ------------------------------------------------------------------ people

  @Transactional
  public List<Notification> inbox(RequestPrincipal me, boolean unreadOnly, int limit) {
    if (me.employeeId() == null) {
      return List.of();
    }
    if (me.email() != null && !me.email().isBlank()) {
      store.rememberEmail(me.tenant(), me.employeeId(), me.email(), clock.instant());
    }
    return store.inbox(
        me.tenant(), me.employeeId(), me.roles(), unreadOnly, Math.clamp(limit, 1, 500));
  }

  @Transactional
  public Notification markRead(RequestPrincipal me, String notificationId) {
    Notification n =
        store
            .find(me.tenant(), notificationId)
            .orElseThrow(() -> new ApiException.NotFound("notification", notificationId));
    boolean mine =
        (n.recipientEmployeeId() != null && n.recipientEmployeeId().equals(me.employeeId()))
            || (n.recipientRole() != null && me.hasRole(n.recipientRole()));
    if (!mine) {
      throw new ApiException.NotFound("notification", notificationId);
    }
    store.markRead(
        me.tenant(), me.employeeIdOrThrow(), me.roles(), notificationId, clock.instant());
    return store.find(me.tenant(), notificationId).orElseThrow();
  }

  @Transactional
  public int markAllRead(RequestPrincipal me) {
    return me.employeeId() == null
        ? 0
        : store.markRead(me.tenant(), me.employeeId(), me.roles(), null, clock.instant());
  }

  @Transactional(readOnly = true)
  public List<Delivery> deliveries(RequestPrincipal me, String notificationId) {
    markReadable(me, notificationId);
    return store.deliveriesOf(me.tenant(), notificationId);
  }

  private void markReadable(RequestPrincipal me, String notificationId) {
    Notification n =
        store
            .find(me.tenant(), notificationId)
            .orElseThrow(() -> new ApiException.NotFound("notification", notificationId));
    boolean mine =
        (n.recipientEmployeeId() != null && n.recipientEmployeeId().equals(me.employeeId()))
            || (n.recipientRole() != null && me.hasRole(n.recipientRole()));
    if (!mine && !me.hasRole("TRAVEL_ADMIN")) {
      throw new ApiException.NotFound("notification", notificationId);
    }
  }

  @Transactional(readOnly = true)
  public List<Delivery> failedDeliveries(RequestPrincipal me) {
    if (!me.hasRole("TRAVEL_ADMIN")) {
      throw new ApiException.Forbidden("NOT_A_TRAVEL_ADMIN", "TRAVEL_ADMIN role required");
    }
    return store.deliveriesByStatus(me.tenant(), DeliveryStatus.FAILED, 200);
  }

  @Transactional
  public Preference preference(RequestPrincipal me) {
    String id = me.employeeIdOrThrow();
    return store
        .preference(me.tenant(), id)
        .orElseGet(
            () -> new Preference(me.tenant(), id, me.email(), null, Map.of(), clock.instant()));
  }

  @Transactional
  public Preference updatePreference(
      RequestPrincipal me,
      @Nullable String email,
      @Nullable String chatHandle,
      Map<String, List<String>> channels) {
    String id = me.employeeIdOrThrow();
    for (Map.Entry<String, List<String>> e : channels.entrySet()) {
      try {
        Category.valueOf(e.getKey());
        e.getValue().forEach(Channel::valueOf);
      } catch (IllegalArgumentException ex) {
        throw new ApiException.Unprocessable(
            "CHANNELS_INVALID",
            "categories are "
                + List.of(Category.values())
                + ", channels "
                + List.of(Channel.values()));
      }
      if (!e.getValue().contains("IN_APP")) {
        throw new ApiException.Unprocessable(
            "IN_APP_REQUIRED", "in-app notifications cannot be switched off");
      }
    }
    Preference p =
        new Preference(
            me.tenant(),
            id,
            email == null || email.isBlank() ? me.email() : email,
            chatHandle,
            channels,
            clock.instant());
    store.upsertPreference(p);
    return store.preference(me.tenant(), id).orElse(p);
  }
}
