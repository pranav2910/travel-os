package io.travelos.learning.model;

import io.travelos.common.tenant.TenantId;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * What the platform announced about a trip; used to authorize feedback and attribute completion.
 */
public record TripRef(
    TenantId tenant,
    String tripId,
    @Nullable String travelerId,
    @Nullable String orderId,
    String status,
    @Nullable Instant bookedAt,
    @Nullable Instant completedAt) {}
