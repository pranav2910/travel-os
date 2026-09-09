package io.travelos.spring.outbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Relays unpublished outbox rows to Kafka in creation order. Multiple instances may run: {@code FOR
 * UPDATE SKIP LOCKED} hands each instance a disjoint batch. A send failure leaves the row
 * unpublished and stops the batch, so ordering within a partition key is preserved and the row is
 * retried on the next poll.
 */
public final class OutboxPublisher {

  private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

  private final JdbcClient jdbc;
  private final TransactionTemplate tx;
  private final KafkaTemplate<String, String> kafka;
  private final OutboxProperties properties;
  private final Clock clock;
  private final Counter published;
  private final Counter failed;

  public OutboxPublisher(
      JdbcClient jdbc,
      TransactionTemplate tx,
      KafkaTemplate<String, String> kafka,
      OutboxProperties properties,
      Clock clock,
      MeterRegistry meters) {
    this.jdbc = jdbc;
    this.tx = tx;
    this.kafka = kafka;
    this.properties = properties;
    this.clock = clock;
    this.published = meters.counter("travelos.outbox.published");
    this.failed = meters.counter("travelos.outbox.failed");
    meters.gauge("travelos.outbox.backlog", this, OutboxPublisher::backlog);
  }

  @Scheduled(fixedDelayString = "${travelos.outbox.poll-interval:PT0.5S}")
  public void relay() {
    int relayed;
    do {
      relayed = relayBatch();
    } while (relayed == properties.batchSize());
  }

  /** One transaction: lock a batch, send each record, mark it published. Returns rows relayed. */
  int relayBatch() {
    Integer count =
        tx.execute(
            status -> {
              List<Pending> batch =
                  jdbc.sql(
                          """
                          SELECT event_id, topic, partition_key, payload::text AS payload
                          FROM outbox
                          WHERE published_at IS NULL
                          ORDER BY created_at, event_id
                          LIMIT :limit
                          FOR UPDATE SKIP LOCKED
                          """)
                      .param("limit", properties.batchSize())
                      .query(Pending.class)
                      .list();
              int sent = 0;
              for (Pending pending : batch) {
                if (!send(pending)) {
                  break;
                }
                jdbc.sql("UPDATE outbox SET published_at = :now WHERE event_id = :eventId")
                    .param("now", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
                    .param("eventId", pending.eventId())
                    .update();
                sent++;
              }
              return sent;
            });
    return count == null ? 0 : count;
  }

  private boolean send(Pending pending) {
    try {
      kafka
          .send(pending.topic(), pending.partitionKey(), pending.payload())
          .get(properties.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
      published.increment();
      return true;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      failed.increment();
      return false;
    } catch (ExecutionException | TimeoutException e) {
      failed.increment();
      log.warn(
          "outbox relay failed for {} on {}: {} — will retry",
          pending.eventId(),
          pending.topic(),
          e.getMessage());
      return false;
    } catch (RuntimeException e) {
      // e.g. KafkaException from a producer that cannot even be constructed (bad config). Loud, but
      // the row stays unpublished and the relay keeps trying rather than killing the scheduler.
      failed.increment();
      log.error(
          "outbox relay cannot send {} on {} — will retry", pending.eventId(), pending.topic(), e);
      return false;
    }
  }

  long backlog() {
    Long count =
        jdbc.sql("SELECT count(*) FROM outbox WHERE published_at IS NULL")
            .query(Long.class)
            .single();
    return count == null ? 0 : count;
  }

  record Pending(String eventId, String topic, String partitionKey, String payload) {}
}
