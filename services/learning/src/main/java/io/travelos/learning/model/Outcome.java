package io.travelos.learning.model;

import io.travelos.common.tenant.TenantId;
import java.time.Instant;
import java.util.Map;
import org.jspecify.annotations.Nullable;

public record Outcome(
    String outcomeId,
    TenantId tenant,
    String logicalKey,
    int revision,
    OutcomeKind kind,
    OutcomeKind.Quality quality,
    @Nullable String supplierKey,
    @Nullable String provider,
    EvidenceClass evidenceClass,
    @Nullable String tripId,
    @Nullable String orderId,
    @Nullable String itemId,
    @Nullable String componentId,
    @Nullable String disruptionId,
    @Nullable String travelerId,
    Instant observedAt,
    Instant recordedAt,
    String source,
    String sourceRef,
    Map<String, Object> provenance) {}
