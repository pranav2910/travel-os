package io.travelos.learning.model;

import io.travelos.common.tenant.TenantId;
import java.time.Instant;
import java.util.Map;
import org.jspecify.annotations.Nullable;

public record Profile(
    String profileId,
    TenantId tenant,
    ProfileStatus status,
    String algorithmVersion,
    EvidenceClass evidenceClass,
    Instant inputCutoff,
    Instant windowStart,
    Map<String, Object> parameters,
    @Nullable String datasetFingerprint,
    int hardOutcomes,
    int feedbackOutcomes,
    int supplierKeys,
    @Nullable Map<String, Object> body,
    @Nullable Map<String, Object> evaluation,
    @Nullable String verdict,
    @Nullable String failureCode,
    @Nullable String failureMessage,
    String requestedBy,
    Instant createdAt,
    @Nullable Instant builtAt,
    @Nullable Instant evaluatedAt,
    @Nullable Instant finishedAt) {}
