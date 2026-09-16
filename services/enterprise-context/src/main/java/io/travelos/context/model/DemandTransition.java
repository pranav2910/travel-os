package io.travelos.context.model;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

public record DemandTransition(
    long id,
    String candidateId,
    @Nullable DemandStatus from,
    DemandStatus to,
    String reason,
    @Nullable String detail,
    String actor,
    Instant occurredAt) {}
