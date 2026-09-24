package io.travelos.assistance.model;

import io.travelos.common.tenant.TenantId;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** A case as stored. Immutable; the repository returns a fresh row after every change. */
public record AssistanceCase(
    String caseId,
    TenantId tenant,
    CaseKind kind,
    CaseStatus status,
    Priority priority,
    Queue queue,
    String title,
    @Nullable String summary,
    @Nullable String tripId,
    @Nullable String orderId,
    @Nullable String travelerId,
    @Nullable String disruptionId,
    @Nullable String exposureId,
    @Nullable String componentId,
    String dedupeKey,
    @Nullable String owner,
    String nextAction,
    String nextActionRole,
    int escalationLevel,
    Instant dueAt,
    Instant openedAt,
    Instant updatedAt,
    @Nullable Instant resolvedAt,
    @Nullable Instant closedAt,
    @Nullable String resolution,
    @Nullable String sourceEventId,
    @Nullable String sourceEventType,
    long version) {

  public boolean overdue(Instant now) {
    return status.open() && dueAt.isBefore(now);
  }
}
