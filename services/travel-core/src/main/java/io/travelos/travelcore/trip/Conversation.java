package io.travelos.travelcore.trip;

import io.travelos.common.identity.Principal;
import io.travelos.common.tenant.TenantId;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** A persisted, multi-turn request for travel; every planning turn is a regular trip. */
public record Conversation(
    String conversationId,
    TenantId tenant,
    String travelerId,
    Principal createdBy,
    Status status,
    @Nullable String currentTripId,
    String idempotencyKey,
    Instant createdAt,
    Instant updatedAt) {

  public enum Status {
    /** A turn is being planned. */
    OPEN,
    /** The platform asked something; the next turn answers it. */
    AWAITING_USER,
    /** The current trip is quoted, awaiting approval, or booked. */
    PLANNED,
    CLOSED
  }

  public record Message(
      String messageId,
      String conversationId,
      int seq,
      Role role,
      String text,
      @Nullable String tripId,
      @Nullable String kind,
      @Nullable String idempotencyKey,
      Instant createdAt) {}

  public enum Role {
    USER,
    ASSISTANT
  }
}
