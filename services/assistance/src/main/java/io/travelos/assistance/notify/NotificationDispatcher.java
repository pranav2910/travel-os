package io.travelos.assistance.notify;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Phase 8: pending deliveries go out on a schedule; nothing is sent inside the event transaction.
 */
@Component
public class NotificationDispatcher {
  private final NotificationService notifications;

  public NotificationDispatcher(NotificationService notifications) {
    this.notifications = notifications;
  }

  @Scheduled(fixedDelayString = "${travelos.assistance.notifications.dispatch-interval:5s}")
  public void dispatch() {
    notifications.dispatch();
  }
}
