package io.travelos.spring.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.travelos.common.tenant.TenantId;
import io.travelos.events.EventCodec;
import io.travelos.events.EventEnvelope;
import io.travelos.events.Topics;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Phase 10: a committed append nudges the relay, so an event leaves within milliseconds even when
 * the safety-net poll is far away (30 s here); a rolled-back append nudges nothing and publishes
 * nothing.
 */
@SpringBootTest(properties = {"travelos.outbox.poll-interval=30s"})
@Testcontainers
class OutboxNudgeIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11");

  @Container @ServiceConnection
  static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1");

  @Autowired Outbox outbox;
  @Autowired TransactionTemplate tx;
  @Autowired JdbcClient jdbc;
  @Autowired KafkaConnectionDetails kafkaConnection;

  private final EventCodec codec = new EventCodec();
  private KafkaConsumer<String, String> consumer;

  @BeforeEach
  void subscribe() {
    Properties props = new Properties();
    props.put(
        ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
        String.join(",", kafkaConnection.getBootstrapServers()));
    props.put(ConsumerConfig.GROUP_ID_CONFIG, "nudge-test-" + System.nanoTime());
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    consumer = new KafkaConsumer<>(props);
    consumer.subscribe(List.of(Topics.TRIP));
    jdbc.sql("DELETE FROM outbox").update();
  }

  @AfterEach
  void close() {
    consumer.close();
  }

  @Test
  void aCommittedAppendIsRelayedAtOnceNotAtTheNextPoll() {
    EventEnvelope event = event("nudged", Map.of("seq", 1));
    long t0 = System.nanoTime();
    tx.executeWithoutResult(status -> outbox.append(event));
    List<ConsumerRecord<String, String>> received = new ArrayList<>();
    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () -> {
              consumer
                  .poll(Duration.ofMillis(100))
                  .forEach(
                      r -> {
                        if ("nudged".equals(r.key())) {
                          received.add(r);
                        }
                      });
              assertThat(received).hasSize(1);
            });
    long millis = (System.nanoTime() - t0) / 1_000_000;
    assertThat(millis).as("well before the 30 s poll").isLessThan(5_000);
    assertThat(codec.fromJson(received.getFirst().value())).isEqualTo(event);
    assertThat(
            jdbc.sql("SELECT count(*) FROM outbox WHERE published_at IS NULL")
                .query(Long.class)
                .single())
        .isZero();
  }

  @Test
  void aRolledBackAppendNudgesNothingAndPublishesNothing() {
    try {
      tx.executeWithoutResult(
          status -> {
            outbox.append(event("rolled-back", Map.of("seq", 2)));
            throw new IllegalStateException("boom");
          });
    } catch (IllegalStateException expected) {
      // the transaction rolled back
    }
    List<ConsumerRecord<String, String>> received = new ArrayList<>();
    long end = System.currentTimeMillis() + 2_000;
    while (System.currentTimeMillis() < end) {
      consumer
          .poll(Duration.ofMillis(200))
          .forEach(
              r -> {
                if ("rolled-back".equals(r.key())) {
                  received.add(r);
                }
              });
    }
    assertThat(received).isEmpty();
    assertThat(jdbc.sql("SELECT count(*) FROM outbox").query(Long.class).single()).isZero();
  }

  private static EventEnvelope event(String key, Map<String, Object> data) {
    return EventEnvelope.create(
        "travel.trip.created", 1, TenantId.of("acme"), key, null, "test", data, Clock.systemUTC());
  }
}
