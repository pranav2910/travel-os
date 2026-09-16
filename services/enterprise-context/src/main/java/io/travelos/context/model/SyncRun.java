package io.travelos.context.model;

import io.travelos.common.tenant.TenantId;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

public record SyncRun(
    String runId,
    String connectorId,
    TenantId tenant,
    Trigger trigger,
    Status status,
    String since,
    String watermark,
    int pages,
    int itemsSeen,
    int itemsChanged,
    int candidatesTouched,
    String lastCursor,
    @Nullable String failureCode,
    @Nullable String failureMessage,
    @Nullable String requestedBy,
    @Nullable String notificationId,
    Instant startedAt,
    @Nullable Instant finishedAt) {
  public enum Trigger {
    SCHEDULE,
    WEBHOOK,
    MANUAL
  }

  public enum Status {
    REQUESTED,
    RUNNING,
    COMPLETED,
    FAILED
  }
}
