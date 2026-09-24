package io.travelos.policy.governance;

import io.travelos.common.money.Money;
import io.travelos.common.tenant.TenantId;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** Phase 7: the governance records. */
public final class GovernanceRecords {
  private GovernanceRecords() {}

  /** Where a trip sits in the organization; null parts are unknown. */
  public record Scope(
      @Nullable String travelerId,
      @Nullable String departmentId,
      @Nullable String costCenterId,
      @Nullable String projectId,
      @Nullable String legalEntityId,
      @Nullable String officeId) {
    public static final Scope NONE = new Scope(null, null, null, null, null, null);
  }

  /** Scope kinds in resolution order: the most specific wins. */
  public enum ScopeKind {
    EMPLOYEE,
    PROJECT,
    COST_CENTER,
    DEPARTMENT,
    LEGAL_ENTITY,
    OFFICE;

    public @Nullable String refIn(Scope s) {
      return switch (this) {
        case EMPLOYEE -> s.travelerId();
        case PROJECT -> s.projectId();
        case COST_CENTER -> s.costCenterId();
        case DEPARTMENT -> s.departmentId();
        case LEGAL_ENTITY -> s.legalEntityId();
        case OFFICE -> s.officeId();
      };
    }
  }

  public record PolicyScope(
      String scopeId,
      TenantId tenant,
      ScopeKind kind,
      String ref,
      String policyId,
      String createdBy,
      Instant createdAt) {}

  public record Budget(
      String budgetId,
      TenantId tenant,
      String name,
      ScopeKind kind,
      String ref,
      Instant periodStart,
      Instant periodEnd,
      String currency,
      long amountMinor,
      long reservedMinor,
      long committedMinor,
      boolean hard,
      boolean active,
      long version,
      String createdBy,
      Instant createdAt,
      Instant updatedAt) {
    public Money remaining() {
      return Money.of(currency, amountMinor - reservedMinor - committedMinor);
    }

    public boolean covers(Instant at) {
      return active && !at.isBefore(periodStart) && at.isBefore(periodEnd);
    }
  }

  public enum ReservationStatus {
    RESERVED,
    COMMITTED,
    RELEASED
  }

  public record Reservation(
      String reservationId,
      String budgetId,
      TenantId tenant,
      String tripId,
      @Nullable String travelerId,
      long amountMinor,
      ReservationStatus status,
      Instant createdAt,
      Instant updatedAt) {}

  public record Agreement(
      String agreementId,
      TenantId tenant,
      String provider,
      String kind,
      @Nullable String carrier,
      @Nullable String rateCode,
      boolean preferred,
      boolean negotiated,
      @Nullable String contractRef,
      Instant validFrom,
      @Nullable Instant validUntil,
      boolean active,
      String createdBy,
      Instant createdAt) {
    public boolean validAt(Instant at) {
      return active && !at.isBefore(validFrom) && (validUntil == null || at.isBefore(validUntil));
    }
  }
}
