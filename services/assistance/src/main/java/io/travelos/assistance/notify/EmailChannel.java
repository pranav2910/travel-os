package io.travelos.assistance.notify;

import io.travelos.assistance.AssistanceProperties;
import io.travelos.assistance.notify.NotificationRecords.Channel;
import io.travelos.assistance.notify.NotificationRecords.Notification;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/** Email through SendGrid's v3 mail/send API (credential-gated). */
public final class EmailChannel implements NotificationChannel {
  private final RestClient http;
  private final AssistanceProperties.Notifications props;

  public EmailChannel(RestClient.Builder builder, AssistanceProperties.Notifications props) {
    this.http = builder.clone().baseUrl(props.sendgridBaseUrl()).build();
    this.props = props;
  }

  @Override
  public Channel channel() {
    return Channel.EMAIL;
  }

  @Override
  public String send(Notification n, String address) {
    Map<String, Object> body =
        Map.of(
            "personalizations", List.of(Map.of("to", List.of(Map.of("email", address)))),
            "from", Map.of("email", props.fromAddress(), "name", "TravelOS"),
            "subject", n.title(),
            "content", List.of(Map.of("type", "text/plain", "value", n.body())),
            "custom_args",
                Map.of("notification_id", n.notificationId(), "tenant_id", n.tenant().value()));
    try {
      var response =
          http.post()
              .uri("/v3/mail/send")
              .header("Authorization", "Bearer " + props.sendgridApiKey())
              .contentType(MediaType.APPLICATION_JSON)
              .body(body)
              .retrieve()
              .toBodilessEntity();
      String id = response.getHeaders().getFirst("X-Message-Id");
      return id == null ? "sendgrid" : id;
    } catch (RestClientResponseException e) {
      int status = e.getStatusCode().value();
      throw new ChannelException("sendgrid answered " + status, status == 429 || status >= 500);
    } catch (ResourceAccessException e) {
      throw new ChannelException("sendgrid unreachable: " + e.getMessage(), true);
    }
  }
}
