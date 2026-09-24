package io.travelos.context.model;

import io.travelos.common.tenant.TenantId;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** A department, legal entity, office or cost center of the tenant (ADR-0014). */
public record OrgUnit(
    TenantId tenant,
    String unitId,
    Kind kind,
    String code,
    String name,
    @Nullable String parentUnitId,
    @Nullable String legalEntityId,
    boolean active,
    long version,
    Instant updatedAt) {
  public enum Kind {
    DEPARTMENT,
    LEGAL_ENTITY,
    OFFICE,
    COST_CENTER
  }
}
