package io.travelos.learning.model;

import io.travelos.common.tenant.TenantId;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

public record Feedback(
    String feedbackId,
    TenantId tenant,
    String tripId,
    String travelerId,
    String componentId,
    @Nullable String supplierKey,
    @Nullable String provider,
    int revision,
    int rating,
    List<String> tags,
    @Nullable String comment,
    String recordedBy,
    Instant recordedAt) {}
