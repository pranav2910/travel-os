package io.travelos.spring.outbox;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.travelos.common.time.Clocks;
import io.travelos.events.EventCodec;
import java.time.Clock;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@AutoConfiguration(
    afterName = {
      "org.springframework.boot.jdbc.autoconfigure.JdbcClientAutoConfiguration",
      "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration",
      "org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration",
      "org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration",
      "org.springframework.boot.opentelemetry.autoconfigure.OpenTelemetrySdkAutoConfiguration"
    })
@ConditionalOnBean({JdbcClient.class, KafkaTemplate.class, PlatformTransactionManager.class})
@EnableConfigurationProperties(OutboxProperties.class)
@EnableScheduling
public class OutboxAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public Clock travelosClock() {
    return Clocks.micros();
  }

  @Bean
  @ConditionalOnMissingBean
  public EventCodec eventCodec() {
    return new EventCodec();
  }

  @Bean
  @ConditionalOnMissingBean(Outbox.class)
  public Outbox outbox(
      JdbcClient jdbc,
      EventCodec codec,
      Clock clock,
      org.springframework.beans.factory.ObjectProvider<OpenTelemetry> otel,
      org.springframework.beans.factory.ObjectProvider<OutboxPublisher> publisher) {
    // Phase 10: the publisher is resolved lazily at nudge time (it may be disabled in tests).
    return new JdbcOutbox(
        jdbc,
        codec,
        clock,
        otel.getIfAvailable(),
        () -> publisher.ifAvailable(OutboxPublisher::nudge));
  }

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnProperty(
      prefix = "travelos.outbox",
      name = "enabled",
      havingValue = "true",
      matchIfMissing = true)
  public OutboxPublisher outboxPublisher(
      JdbcClient jdbc,
      PlatformTransactionManager transactionManager,
      KafkaTemplate<String, String> kafka,
      OutboxProperties properties,
      Clock clock,
      org.springframework.beans.factory.ObjectProvider<MeterRegistry> meters,
      org.springframework.beans.factory.ObjectProvider<OpenTelemetry> otel) {
    MeterRegistry registry = meters.getIfAvailable(SimpleMeterRegistry::new);
    return new OutboxPublisher(
        jdbc,
        new TransactionTemplate(transactionManager),
        kafka,
        properties,
        clock,
        registry,
        otel.getIfAvailable());
  }
}
