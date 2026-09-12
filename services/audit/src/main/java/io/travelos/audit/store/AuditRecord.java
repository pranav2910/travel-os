package io.travelos.audit.store;

import java.time.Instant;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** One stored event, as the API returns it. */
public record AuditRecord(
    String eventId,
    String eventType,
    int eventVersion,
    Instant occurredAt,
    Instant receivedAt,
    String tenantId,
    String correlationId,
    @Nullable String causationId,
    String producer,
    Map<String, Object> data) {}
