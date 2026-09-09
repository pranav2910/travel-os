package io.travelos.spring.outbox;

import io.travelos.events.EventEnvelope;

/**
 * Append an event in the caller's current transaction. If the transaction rolls back, the event was
 * never published; if it commits, the event will be published (at least once). Call it from the
 * same {@code @Transactional} method that changes state — never from outside a transaction.
 */
public interface Outbox {

  void append(EventEnvelope event);
}
