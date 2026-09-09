package io.travelos.spring.outbox;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
      "org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration"
    })
@ConditionalOnBean({JdbcClient.class, KafkaTemplate.class, PlatformTransactionManager.class})
@EnableConfigurationProperties(OutboxProperties.class)
@EnableScheduling
public class OutboxAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public Clock travelosClock() {
    return Clock.systemUTC();
  }

  @Bean
  @ConditionalOnMissingBean
  public EventCodec eventCodec() {
    return new EventCodec();
  }

  @Bean
  @ConditionalOnMissingBean(Outbox.class)
  public Outbox outbox(JdbcClient jdbc, EventCodec codec, Clock clock) {
    return new JdbcOutbox(jdbc, codec, clock);
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
      org.springframework.beans.factory.ObjectProvider<MeterRegistry> meters) {
    MeterRegistry registry = meters.getIfAvailable(SimpleMeterRegistry::new);
    return new OutboxPublisher(
        jdbc, new TransactionTemplate(transactionManager), kafka, properties, clock, registry);
  }
}
