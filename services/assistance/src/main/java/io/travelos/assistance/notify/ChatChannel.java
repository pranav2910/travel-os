package io.travelos.assistance.notify;

import io.travelos.assistance.AssistanceProperties;
import io.travelos.assistance.notify.NotificationRecords.Channel;
import io.travelos.assistance.notify.NotificationRecords.Notification;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Chat through a Slack incoming webhook (credential-gated). The webhook posts to its configured
 * channel; the person's handle is mentioned so the message reaches them.
 */
public final class ChatChannel implements NotificationChannel {
  private final RestClient http;
  private final AssistanceProperties.Notifications props;

  public ChatChannel(RestClient.Builder builder, AssistanceProperties.Notifications props) {
    this.http = builder.clone().build();
    this.props = props;
  }

  @Override
  public Channel channel() {
    return Channel.CHAT;
  }

  @Override
  public String send(Notification n, String handle) {
    String mention =
        handle.startsWith("U") && !handle.contains(" ") ? "<@" + handle + "> " : handle + " ";
    Map<String, Object> body = Map.of("text", mention + "*" + n.title() + "*\n" + n.body());
    try {
      http.post()
          .uri(java.net.URI.create(props.slackWebhookUrl()))
          .contentType(MediaType.APPLICATION_JSON)
          .body(body)
          .retrieve()
          .toBodilessEntity();
      return "slack-webhook";
    } catch (RestClientResponseException e) {
      int status = e.getStatusCode().value();
      throw new ChannelException("slack answered " + status, status == 429 || status >= 500);
    } catch (ResourceAccessException e) {
      throw new ChannelException("slack unreachable: " + e.getMessage(), true);
    }
  }
}
