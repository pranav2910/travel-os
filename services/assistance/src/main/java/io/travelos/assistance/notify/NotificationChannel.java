package io.travelos.assistance.notify;

import io.travelos.assistance.notify.NotificationRecords.Channel;
import io.travelos.assistance.notify.NotificationRecords.Notification;

/** A way out of the platform. Implementations are registered only when their credentials exist. */
public interface NotificationChannel {
  Channel channel();

  /**
   * @return the provider's reference for the message
   * @throws ChannelException when the message did not go out (retryable says whether to try again)
   */
  String send(Notification notification, String address);

  final class ChannelException extends RuntimeException {
    private final boolean retryable;

    public ChannelException(String message, boolean retryable) {
      super(message);
      this.retryable = retryable;
    }

    public boolean retryable() {
      return retryable;
    }
  }
}
