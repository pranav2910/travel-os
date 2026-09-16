package io.travelos.context.model;

import io.travelos.common.tenant.TenantId;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A detected need to travel: who (a verified employee), where, when, why we think so, what is still
 * missing, and every source that contributed. Identity is stable across redeliveries because the
 * primary commitment keys it.
 */
public record DemandCandidate(
    String candidateId,
    TenantId tenant,
    String travelerId,
    DemandStatus status,
    @Nullable String origin,
    @Nullable String destination,
    @Nullable LocalDate startDate,
    @Nullable LocalDate endDate,
    @Nullable String timeZone,
    @Nullable Instant windowStart,
    @Nullable Instant windowEnd,
    @Nullable String purpose,
    List<String> missing,
    List<String> reviewReasons,
    List<SourceRef> sources,
    String rulesVersion,
    String explanation,
    @Nullable String tripId,
    @Nullable String conversionKey,
    String primaryKey,
    long version,
    Instant createdAt,
    Instant updatedAt) {
  public DemandCandidate {
    missing = List.copyOf(missing);
    reviewReasons = List.copyOf(reviewReasons);
    sources = List.copyOf(sources);
  }

  public boolean complete() {
    return destination != null && startDate != null && endDate != null && origin != null;
  }
}
