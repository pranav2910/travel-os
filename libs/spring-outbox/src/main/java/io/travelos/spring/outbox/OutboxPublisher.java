package io.travelos.spring.outbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.jspecify.annotations.Nullable;
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
 *
 * <p>Within one instance only one relay runs at a time: the scheduled poll and the after-commit
 * nudge (Phase 10) take the same lock. Two concurrent relays would each lock part of the same
 * backlog and publish their parts in whatever order they finished, which reorders events that share
 * a partition key (seen once in CI as seq 4 before seq 3).
 */
public final class OutboxPublisher {

  private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

  private final JdbcClient jdbc;
  private final TransactionTemplate tx;
  private final KafkaTemplate<String, String> kafka;
  private final OutboxProperties properties;
  private final Clock clock;
  private final @Nullable OpenTelemetry otel;
  private final Counter published;
  private final Counter failed;

  public OutboxPublisher(
      JdbcClient jdbc,
      TransactionTemplate tx,
      KafkaTemplate<String, String> kafka,
      OutboxProperties properties,
      Clock clock,
      MeterRegistry meters) {
    this(jdbc, tx, kafka, properties, clock, meters, null);
  }

  public OutboxPublisher(
      JdbcClient jdbc,
      TransactionTemplate tx,
      KafkaTemplate<String, String> kafka,
      OutboxProperties properties,
      Clock clock,
      MeterRegistry meters,
      @Nullable OpenTelemetry otel) {
    this.jdbc = jdbc;
    this.tx = tx;
    this.kafka = kafka;
    this.properties = properties;
    this.clock = clock;
    this.otel = otel;
    this.published = meters.counter("travelos.outbox.published");
    this.failed = meters.counter("travelos.outbox.failed");
    meters.gauge("travelos.outbox.backlog", this, OutboxPublisher::backlog);
  }

  private final java.util.concurrent.locks.ReentrantLock relaying =
      new java.util.concurrent.locks.ReentrantLock();

  @Scheduled(fixedDelayString = "${travelos.outbox.poll-interval:PT0.5S}")
  public void relay() {
    relaying.lock();
    try {
      int relayed;
      do {
        relayed = relayBatch();
      } while (relayed == properties.batchSize());
    } finally {
      relaying.unlock();
    }
  }

  // ---- Phase 10: an appending transaction that committed asks for a relay now, not at the next
  // poll. One relay thread; nudges that arrive while one runs collapse into a single follow-up
  // pass (the flag), so a burst of commits costs one extra pass, not one per commit. The poll
  // stays as the safety net for anything the nudge misses (a crash between commit and relay).
  private final java.util.concurrent.ExecutorService nudger =
      java.util.concurrent.Executors.newSingleThreadExecutor(
          r -> {
            Thread t = new Thread(r, "outbox-nudge");
            t.setDaemon(true);
            return t;
          });
  private final java.util.concurrent.atomic.AtomicBoolean nudgePending =
      new java.util.concurrent.atomic.AtomicBoolean();

  public void nudge() {
    if (nudgePending.compareAndSet(false, true)) {
      try {
        nudger.execute(
            () -> {
              nudgePending.set(false);
              try {
                relay();
              } catch (RuntimeException e) {
                log.warn("nudged outbox relay failed; the poll will retry: {}", e.getMessage());
              }
            });
      } catch (java.util.concurrent.RejectedExecutionException e) {
        nudgePending.set(false);
      }
    }
  }

  /** One transaction: lock a batch, send each record, mark it published. Returns rows relayed. */
  int relayBatch() {
    Integer count =
        tx.execute(
            status -> {
              List<Pending> batch =
                  jdbc.sql(
                          """
                          SELECT event_id, topic, partition_key, payload::text AS payload,
                                 trace_parent
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
      kafka.send(record(pending)).get(properties.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
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

  /**
   * The record, carrying the trace of the transaction that appended it. A PRODUCER span is opened
   * as a child of that trace so the relay itself is visible; consumers continue from the header.
   */
  private ProducerRecord<String, String> record(Pending pending) {
    ProducerRecord<String, String> record =
        new ProducerRecord<>(pending.topic(), pending.partitionKey(), pending.payload());
    if (otel == null) {
      return record;
    }
    Context parent = TraceContexts.parent(otel, pending.traceParent());
    Span span =
        otel.getTracer("travelos.outbox")
            .spanBuilder("outbox publish " + pending.topic())
            .setParent(parent)
            .setSpanKind(SpanKind.PRODUCER)
            .setAttribute("messaging.system", "kafka")
            .setAttribute("messaging.destination.name", pending.topic())
            .setAttribute("messaging.message.id", pending.eventId())
            .setAttribute("trip.id", pending.partitionKey())
            .startSpan();
    try {
      TraceContexts.headers(otel, Context.current().with(span))
          .forEach((k, v) -> record.headers().add(k, v.getBytes(StandardCharsets.UTF_8)));
    } finally {
      span.end();
    }
    return record;
  }

  long backlog() {
    Long count =
        jdbc.sql("SELECT count(*) FROM outbox WHERE published_at IS NULL")
            .query(Long.class)
            .single();
    return count == null ? 0 : count;
  }

  record Pending(
      String eventId,
      String topic,
      String partitionKey,
      String payload,
      @Nullable String traceParent) {}
}
