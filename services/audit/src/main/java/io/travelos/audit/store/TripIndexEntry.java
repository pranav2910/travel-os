package io.travelos.audit.store;

import java.time.Instant;

public record TripIndexEntry(
    String tenantId, String tripId, String travelerId, Instant createdAt) {}
