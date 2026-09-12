package io.travelos.spring.outbox;

import io.opentelemetry.api.OpenTelemetry;
import io.travelos.events.EventCodec;
import io.travelos.events.EventEnvelope;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public final class JdbcOutbox implements Outbox {

  private final JdbcClient jdbc;
  private final EventCodec codec;
  private final Clock clock;
  private final @Nullable OpenTelemetry otel;

  public JdbcOutbox(JdbcClient jdbc, EventCodec codec, Clock clock) {
    this(jdbc, codec, clock, null);
  }

  public JdbcOutbox(JdbcClient jdbc, EventCodec codec, Clock clock, @Nullable OpenTelemetry otel) {
    this.jdbc = jdbc;
    this.codec = codec;
    this.clock = clock;
    this.otel = otel;
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
  }
}
