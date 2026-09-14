package io.travelos.disruption.model;

import io.travelos.common.tenant.TenantId;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * The canonical disruption. Ids point at evidence owned by other services (trip, order, policy
 * decision, optimization run); {@code affectedJson} and {@code recoveryJson} are protobuf JSON of
 * travelos.disruption.v1.AffectedSegment / RecoveryState.
 */
public record Disruption(
    String disruptionId,
    TenantId tenant,
    @Nullable String tripId,
    @Nullable String orderId,
    @Nullable String travelerId,
    @Nullable String segmentId,
    String type,
    String supplier,
    String supplierEventId,
    String externalOrderId,
    @Nullable String recordLocator,
    Instant detectedAt,
    DisruptionStatus status,
    String severity,
    @Nullable String rawReference,
    @Nullable String reason,
    String affectedJson,
    String recoveryJson,
    @Nullable String failureStage,
    @Nullable String failureCode,
    String sourceEventId,
    long version,
    Instant createdAt,
    Instant updatedAt) {}
