package io.travelos.assistance.notify;

import io.travelos.assistance.AssistanceProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/** Phase 8: delivery channels exist only when their credentials do; in-app always. */
@Configuration(proxyBeanMethods = false)
class NotificationConfiguration {
  private static final Logger log = LoggerFactory.getLogger(NotificationConfiguration.class);

  @Bean
  @ConditionalOnExpression("'${travelos.assistance.notifications.sendgrid-api-key:}' != ''")
  EmailChannel emailChannel(AssistanceProperties properties, RestClient.Builder builder) {
    log.info(
        "email notifications through sendgrid from {}", properties.notifications().fromAddress());
    return new EmailChannel(builder, properties.notifications());
  }

  @Bean
  @ConditionalOnExpression("'${travelos.assistance.notifications.slack-webhook-url:}' != ''")
  ChatChannel chatChannel(AssistanceProperties properties, RestClient.Builder builder) {
    log.info("chat notifications through a slack webhook");
    return new ChatChannel(builder, properties.notifications());
  }
}
