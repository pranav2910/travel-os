package io.travelos.spring.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

@SpringBootTest
@Testcontainers
class OutboxIntegrationTest {

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
    props.put(ConsumerConfig.GROUP_ID_CONFIG, "outbox-test-" + System.nanoTime());
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
  void committedEventsReachKafkaInOrderWithTheCorrelationKey() {
    List<EventEnvelope> events = new ArrayList<>();
    tx.executeWithoutResult(
        status -> {
          for (int i = 0; i < 5; i++) {
            EventEnvelope event = event("trip_ordered", Map.of("seq", i));
            events.add(event);
            outbox.append(event);
          }
        });

    List<ConsumerRecord<String, String>> received = new ArrayList<>();
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () -> {
              consumer.poll(Duration.ofMillis(200)).forEach(received::add);
              assertThat(received).hasSize(5);
            });

    assertThat(received).allSatisfy(record -> assertThat(record.key()).isEqualTo("trip_ordered"));
    assertThat(received.stream().map(record -> codec.fromJson(record.value())))
        .containsExactlyElementsOf(events);
    assertThat(
            jdbc.sql("SELECT count(*) FROM outbox WHERE published_at IS NULL")
                .query(Long.class)
                .single())
        .isZero();
  }

  @Test
  void rolledBackEventsAreNeverPublished() {
    assertThatThrownBy(
            () ->
                tx.executeWithoutResult(
                    status -> {
                      outbox.append(event("trip_rolled_back", Map.of()));
                      throw new IllegalStateException("business rule failed after append");
                    }))
        .isInstanceOf(IllegalStateException.class);

    assertThat(jdbc.sql("SELECT count(*) FROM outbox").query(Long.class).single()).isZero();
    assertThat(consumer.poll(Duration.ofSeconds(2)).count()).isZero();
  }

  @Test
  void appendOutsideATransactionIsARefusedProgrammingError() {
    assertThatThrownBy(() -> outbox.append(event("trip_no_tx", Map.of())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("inside the transaction");
  }

  private static EventEnvelope event(String correlationId, Map<String, Object> data) {
    return EventEnvelope.create(
        "travel.trip.created",
        1,
        TenantId.of("acme"),
        correlationId,
        null,
        "outbox-test",
        data,
        Clock.systemUTC());
  }
}
