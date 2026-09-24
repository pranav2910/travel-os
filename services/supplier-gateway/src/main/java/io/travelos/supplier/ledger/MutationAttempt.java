package io.travelos.supplier.ledger;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** One supplier mutation as the gateway remembers it (ADR-0016). */
public record MutationAttempt(
    String attemptId,
    String tenantId,
    String provider,
    Command command,
    String idempotencyKey,
    String requestDigest,
    Status status,
    @Nullable String externalRef,
    byte @Nullable [] response,
    @Nullable String failureCode,
    @Nullable String failureMessage,
    int calls,
    @Nullable String correlationId,
    Instant createdAt,
    Instant updatedAt) {

  public enum Command {
    CREATE,
    CHANGE,
    CANCEL
  }

  public enum Status {
    /** Written before the supplier was called; the call is in flight or died with the process. */
    STARTED,
    SUCCEEDED,
    /**
     * The supplier answered with a final refusal; retrying with the same key gets the same answer.
     */
    FAILED,
    /** The answer was lost (timeout, transport); the supplier may or may not have acted. */
    UNKNOWN
  }
}
