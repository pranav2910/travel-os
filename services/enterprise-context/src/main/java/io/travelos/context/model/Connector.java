package io.travelos.context.model;

import io.travelos.common.tenant.TenantId;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * One tenant's connection to one enterprise system. {@code config} never holds a secret: providers
 * name their secrets and the service resolves them from its environment.
 */
public record Connector(
    String connectorId,
    TenantId tenant,
    ConnectorKind kind,
    String provider,
    Status status,
    String configJson,
    String checkpoint,
    @Nullable String runningRunId,
    @Nullable Instant nextSyncAt,
    @Nullable String lastRunId,
    @Nullable Instant lastSyncAt,
    @Nullable Instant lastSuccessAt,
    @Nullable String lastErrorCode,
    @Nullable String lastErrorMessage,
    long version,
    String createdBy,
    Instant createdAt,
    Instant updatedAt) {
  public enum Status {
    ENABLED,
    DISABLED
  }

  /** SIMULATED providers are named so nobody mistakes them for a live integration. */
  public boolean simulated() {
    return provider.startsWith("sandbox-");
  }
}
