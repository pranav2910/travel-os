package io.travelos.spring.outbox;

import io.opentelemetry.api.OpenTelemetry;
import io.travelos.events.EventCodec;
import io.travelos.events.EventEnvelope;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public final class JdbcOutbox implements Outbox {

  private final JdbcClient jdbc;
  private final EventCodec codec;
  private final Clock clock;
  private final @Nullable OpenTelemetry otel;
  private final Runnable afterCommit;

  public JdbcOutbox(JdbcClient jdbc, EventCodec codec, Clock clock) {
    this(jdbc, codec, clock, null);
  }

  public JdbcOutbox(JdbcClient jdbc, EventCodec codec, Clock clock, @Nullable OpenTelemetry otel) {
    this(jdbc, codec, clock, otel, () -> {});
  }

  /**
   * @param afterCommit Phase 10: run once the appending transaction has committed (the publisher's
   *     nudge), so a relay does not wait for the next poll. Never runs on rollback.
   */
  public JdbcOutbox(
      JdbcClient jdbc,
      EventCodec codec,
      Clock clock,
      @Nullable OpenTelemetry otel,
      Runnable afterCommit) {
    this.jdbc = jdbc;
    this.codec = codec;
    this.clock = clock;
    this.otel = otel;
    this.afterCommit = afterCommit;
  }

  @Override
  public void append(EventEnvelope event) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      // Silently appending outside a transaction would break the one guarantee this class exists
      // for.
      throw new IllegalStateException(
          "Outbox.append must be called inside the transaction that changes state");
    }
    jdbc.sql(
            """
            INSERT INTO outbox (event_id, topic, partition_key, payload, created_at, trace_parent)
            VALUES (:eventId, :topic, :partitionKey, CAST(:payload AS jsonb), :createdAt, :traceParent)
            """)
        .param("eventId", event.eventId())
        .param("topic", event.topic())
        .param("partitionKey", event.partitionKey())
        .param("payload", codec.toJson(event))
        .param("createdAt", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
        .param("traceParent", TraceContexts.current(otel))
        .update();
    // One nudge per transaction, however many events it appends; nothing on rollback.
    if (!TransactionSynchronizationManager.getSynchronizations().stream()
        .anyMatch(s -> s instanceof Nudge)) {
      TransactionSynchronizationManager.registerSynchronization(new Nudge(afterCommit));
    }
  }

  private static final class Nudge implements TransactionSynchronization {
    private final Runnable afterCommit;

    Nudge(Runnable afterCommit) {
      this.afterCommit = afterCommit;
    }

    @Override
    public void afterCommit() {
      afterCommit.run();
    }
  }
}
