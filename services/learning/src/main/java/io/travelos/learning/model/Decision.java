package io.travelos.learning.model;

import io.travelos.common.tenant.TenantId;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** One optimizer run as announced on travel.optimization. */
public record Decision(
    String decisionId,
    TenantId tenant,
    String tripId,
    EvidenceClass evidenceClass,
    Instant decidedAt,
    @Nullable String selectedId,
    List<String> selectedKeys,
    List<Map<String, Object>> candidates,
    @Nullable Map<String, Object> learning,
    String sourceEventId) {}
