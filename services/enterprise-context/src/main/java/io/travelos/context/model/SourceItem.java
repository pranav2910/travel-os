package io.travelos.context.model;

import io.travelos.common.tenant.TenantId;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** The latest revision of one thing a source told us, normalized into our vocabulary. */
public record SourceItem(
    TenantId tenant,
    String connectorId,
    String sourceId,
    ConnectorKind kind,
    long revision,
    Status status,
    String normalizedJson,
    @Nullable String candidateId,
    Instant firstSeenAt,
    Instant observedAt) {
  public enum Status {
    ACTIVE,
    CANCELLED,
    DELETED
  }
}
