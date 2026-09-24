package io.travelos.context.model;

import io.travelos.common.tenant.TenantId;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * An explicit permission to arrange travel for others: one employee, everyone in an org unit, every
 * member of a project, or the whole tenant. {@code mayReadDocuments} is a separate, narrower
 * permission: booking needs the passenger's documents, arranging a domestic trip does not.
 */
public record ArrangerGrant(
    TenantId tenant,
    String grantId,
    String arrangerEmployeeId,
    Scope scope,
    @Nullable String scopeId,
    boolean mayReadDocuments,
    String grantedBy,
    Instant grantedAt,
    @Nullable Instant expiresAt,
    @Nullable Instant revokedAt) {

  public enum Scope {
    EMPLOYEE,
    ORG_UNIT,
    PROJECT,
    TENANT
  }

  public boolean activeAt(Instant now) {
    return revokedAt == null && (expiresAt == null || expiresAt.isAfter(now));
  }
}
