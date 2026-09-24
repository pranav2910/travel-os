package io.travelos.policy.governance;

import io.travelos.common.tenant.TenantId;
import io.travelos.policy.governance.GovernanceRecords.Agreement;
import io.travelos.policy.governance.GovernanceRecords.Budget;
import io.travelos.policy.governance.GovernanceRecords.PolicyScope;
import io.travelos.policy.governance.GovernanceRecords.Reservation;
import io.travelos.policy.governance.GovernanceRecords.ReservationStatus;
import io.travelos.policy.governance.GovernanceRecords.ScopeKind;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class GovernanceRepository {
  private final JdbcClient jdbc;

  public GovernanceRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  // ------------------------------------------------------------------ scopes

  public void upsertScope(PolicyScope s) {
    jdbc.sql(
            """
            INSERT INTO policy_scope (scope_id, tenant_id, scope_kind, scope_ref, policy_id, created_by, created_at)
            VALUES (:id, :tenant, :kind, :ref, :policy, :by, :at)
            ON CONFLICT (tenant_id, scope_kind, scope_ref) DO UPDATE SET
              policy_id = EXCLUDED.policy_id, created_by = EXCLUDED.created_by, created_at = EXCLUDED.created_at
            """)
        .param("id", s.scopeId())
        .param("tenant", s.tenant().value())
        .param("kind", s.kind().name())
        .param("ref", s.ref())
        .param("policy", s.policyId())
        .param("by", s.createdBy())
        .param("at", Timestamp.from(s.createdAt()))
        .update();
  }

  public boolean deleteScope(TenantId tenant, ScopeKind kind, String ref) {
    return jdbc.sql(
                "DELETE FROM policy_scope WHERE tenant_id = :t AND scope_kind = :k AND scope_ref = :r")
            .param("t", tenant.value())
            .param("k", kind.name())
            .param("r", ref)
            .update()
        == 1;
  }

  public List<PolicyScope> scopes(TenantId tenant) {
    return jdbc.sql(
            "SELECT * FROM policy_scope WHERE tenant_id = :t ORDER BY scope_kind, scope_ref")
        .param("t", tenant.value())
        .query(GovernanceRepository::scope)
        .list();
  }

  public Optional<PolicyScope> scope(TenantId tenant, ScopeKind kind, String ref) {
    return jdbc.sql(
            "SELECT * FROM policy_scope WHERE tenant_id = :t AND scope_kind = :k AND scope_ref = :r")
        .param("t", tenant.value())
        .param("k", kind.name())
        .param("r", ref)
        .query(GovernanceRepository::scope)
        .optional();
  }

  // ------------------------------------------------------------------ budgets

  public void insertBudget(Budget b) {
    jdbc.sql(
            """
            INSERT INTO budget (budget_id, tenant_id, name, scope_kind, scope_ref, period_start, period_end, currency,
              amount_minor, reserved_minor, committed_minor, hard, active, version, created_by, created_at, updated_at)
            VALUES (:id, :tenant, :name, :kind, :ref, :start, :end, :currency, :amount, 0, 0, :hard, TRUE, 0, :by, :at, :at)
            """)
        .param("id", b.budgetId())
        .param("tenant", b.tenant().value())
        .param("name", b.name())
        .param("kind", b.kind().name())
        .param("ref", b.ref())
        .param("start", Timestamp.from(b.periodStart()))
        .param("end", Timestamp.from(b.periodEnd()))
        .param("currency", b.currency())
        .param("amount", b.amountMinor())
        .param("hard", b.hard())
        .param("by", b.createdBy())
        .param("at", Timestamp.from(b.createdAt()))
        .update();
  }

  public List<Budget> budgets(TenantId tenant) {
    return jdbc.sql(
            "SELECT * FROM budget WHERE tenant_id = :t ORDER BY period_start DESC, scope_kind, scope_ref")
        .param("t", tenant.value())
        .query(GovernanceRepository::budget)
        .list();
  }

  public Optional<Budget> budget(TenantId tenant, String budgetId) {
    return jdbc.sql("SELECT * FROM budget WHERE tenant_id = :t AND budget_id = :id")
        .param("t", tenant.value())
        .param("id", budgetId)
        .query(GovernanceRepository::budget)
        .optional();
  }

  /**
   * The active budgets for one scope reference that cover the instant, most recent period first.
   */
  public List<Budget> budgetsFor(
      TenantId tenant, ScopeKind kind, String ref, Instant at, boolean lock) {
    return jdbc.sql(
            "SELECT * FROM budget WHERE tenant_id = :t AND scope_kind = :k AND scope_ref = :r AND active"
                + " AND period_start <= :at AND period_end > :at ORDER BY period_start DESC"
                + (lock ? " FOR UPDATE" : ""))
        .param("t", tenant.value())
        .param("k", kind.name())
        .param("r", ref)
        .param("at", Timestamp.from(at))
        .query(GovernanceRepository::budget)
        .list();
  }

  public Optional<Budget> lockBudget(String budgetId) {
    return jdbc.sql("SELECT * FROM budget WHERE budget_id = :id FOR UPDATE")
        .param("id", budgetId)
        .query(GovernanceRepository::budget)
        .optional();
  }

  public void moveBudget(String budgetId, long reservedDelta, long committedDelta, Instant now) {
    jdbc.sql(
            "UPDATE budget SET reserved_minor = reserved_minor + :r, committed_minor = committed_minor + :c,"
                + " version = version + 1, updated_at = :now WHERE budget_id = :id")
        .param("r", reservedDelta)
        .param("c", committedDelta)
        .param("now", Timestamp.from(now))
        .param("id", budgetId)
        .update();
  }

  public boolean deactivateBudget(TenantId tenant, String budgetId, Instant now) {
    return jdbc.sql(
                "UPDATE budget SET active = FALSE, version = version + 1, updated_at = :now WHERE tenant_id = :t AND budget_id = :id AND active")
            .param("now", Timestamp.from(now))
            .param("t", tenant.value())
            .param("id", budgetId)
            .update()
        == 1;
  }

  // ------------------------------------------------------------------ reservations

  public void insertReservation(Reservation r) {
    jdbc.sql(
            """
            INSERT INTO budget_reservation (reservation_id, budget_id, tenant_id, trip_id, traveler_id, amount_minor, status, created_at, updated_at)
            VALUES (:id, :budget, :tenant, :trip, :traveler, :amount, :status, :at, :at)
            """)
        .param("id", r.reservationId())
        .param("budget", r.budgetId())
        .param("tenant", r.tenant().value())
        .param("trip", r.tripId())
        .param("traveler", r.travelerId())
        .param("amount", r.amountMinor())
        .param("status", r.status().name())
        .param("at", Timestamp.from(r.createdAt()))
        .update();
  }

  public void updateReservation(
      String reservationId, ReservationStatus status, long amountMinor, Instant now) {
    jdbc.sql(
            "UPDATE budget_reservation SET status = :s, amount_minor = :a, updated_at = :now WHERE reservation_id = :id")
        .param("s", status.name())
        .param("a", amountMinor)
        .param("now", Timestamp.from(now))
        .param("id", reservationId)
        .update();
  }

  public Optional<Reservation> reservation(String budgetId, String tripId) {
    return jdbc.sql("SELECT * FROM budget_reservation WHERE budget_id = :b AND trip_id = :t")
        .param("b", budgetId)
        .param("t", tripId)
        .query(GovernanceRepository::reservation)
        .optional();
  }

  public List<Reservation> reservationsOfTrip(TenantId tenant, String tripId) {
    return jdbc.sql(
            "SELECT * FROM budget_reservation WHERE tenant_id = :t AND trip_id = :trip ORDER BY created_at")
        .param("t", tenant.value())
        .param("trip", tripId)
        .query(GovernanceRepository::reservation)
        .list();
  }

  public List<Reservation> reservationsOfBudget(TenantId tenant, String budgetId, int limit) {
    return jdbc.sql(
            "SELECT * FROM budget_reservation WHERE tenant_id = :t AND budget_id = :b ORDER BY created_at DESC LIMIT :n")
        .param("t", tenant.value())
        .param("b", budgetId)
        .param("n", limit)
        .query(GovernanceRepository::reservation)
        .list();
  }

  // ------------------------------------------------------------------ agreements

  public void insertAgreement(Agreement a) {
    jdbc.sql(
            """
            INSERT INTO supplier_agreement (agreement_id, tenant_id, provider, kind, carrier, rate_code, preferred, negotiated,
              contract_ref, valid_from, valid_until, active, created_by, created_at)
            VALUES (:id, :tenant, :provider, :kind, :carrier, :rate, :preferred, :negotiated, :contract, :from, :until, TRUE, :by, :at)
            """)
        .param("id", a.agreementId())
        .param("tenant", a.tenant().value())
        .param("provider", a.provider())
        .param("kind", a.kind())
        .param("carrier", a.carrier())
        .param("rate", a.rateCode())
        .param("preferred", a.preferred())
        .param("negotiated", a.negotiated())
        .param("contract", a.contractRef())
        .param("from", Timestamp.from(a.validFrom()))
        .param("until", a.validUntil() == null ? null : Timestamp.from(a.validUntil()))
        .param("by", a.createdBy())
        .param("at", Timestamp.from(a.createdAt()))
        .update();
  }

  public List<Agreement> agreements(TenantId tenant, boolean activeOnly) {
    return jdbc.sql(
            "SELECT * FROM supplier_agreement WHERE tenant_id = :t"
                + (activeOnly ? " AND active" : "")
                + " ORDER BY kind, provider, created_at")
        .param("t", tenant.value())
        .query(GovernanceRepository::agreement)
        .list();
  }

  public boolean deactivateAgreement(TenantId tenant, String agreementId) {
    return jdbc.sql(
                "UPDATE supplier_agreement SET active = FALSE WHERE tenant_id = :t AND agreement_id = :id AND active")
            .param("t", tenant.value())
            .param("id", agreementId)
            .update()
        == 1;
  }

  // ------------------------------------------------------------------ processed events

  public boolean markProcessed(String eventId, String eventType, Instant now) {
    return jdbc.sql(
                "INSERT INTO processed_event (event_id, event_type, processed_at) VALUES (:id, :type, :now) ON CONFLICT (event_id) DO NOTHING")
            .param("id", eventId)
            .param("type", eventType)
            .param("now", Timestamp.from(now))
            .update()
        == 1;
  }

  // ------------------------------------------------------------------ mappers

  private static PolicyScope scope(ResultSet rs, int i) throws SQLException {
    return new PolicyScope(
        rs.getString("scope_id"),
        TenantId.of(rs.getString("tenant_id")),
        ScopeKind.valueOf(rs.getString("scope_kind")),
        rs.getString("scope_ref"),
        rs.getString("policy_id"),
        rs.getString("created_by"),
        instant(rs, "created_at"));
  }

  private static Budget budget(ResultSet rs, int i) throws SQLException {
    return new Budget(
        rs.getString("budget_id"),
        TenantId.of(rs.getString("tenant_id")),
        rs.getString("name"),
        ScopeKind.valueOf(rs.getString("scope_kind")),
        rs.getString("scope_ref"),
        instant(rs, "period_start"),
        instant(rs, "period_end"),
        rs.getString("currency").trim(),
        rs.getLong("amount_minor"),
        rs.getLong("reserved_minor"),
        rs.getLong("committed_minor"),
        rs.getBoolean("hard"),
        rs.getBoolean("active"),
        rs.getLong("version"),
        rs.getString("created_by"),
        instant(rs, "created_at"),
        instant(rs, "updated_at"));
  }

  private static Reservation reservation(ResultSet rs, int i) throws SQLException {
    return new Reservation(
        rs.getString("reservation_id"),
        rs.getString("budget_id"),
        TenantId.of(rs.getString("tenant_id")),
        rs.getString("trip_id"),
        rs.getString("traveler_id"),
        rs.getLong("amount_minor"),
        ReservationStatus.valueOf(rs.getString("status")),
        instant(rs, "created_at"),
        instant(rs, "updated_at"));
  }

  private static Agreement agreement(ResultSet rs, int i) throws SQLException {
    OffsetDateTime until = rs.getObject("valid_until", OffsetDateTime.class);
    return new Agreement(
        rs.getString("agreement_id"),
        TenantId.of(rs.getString("tenant_id")),
        rs.getString("provider"),
        rs.getString("kind"),
        rs.getString("carrier"),
        rs.getString("rate_code"),
        rs.getBoolean("preferred"),
        rs.getBoolean("negotiated"),
        rs.getString("contract_ref"),
        instant(rs, "valid_from"),
        until == null ? null : until.toInstant(),
        rs.getBoolean("active"),
        rs.getString("created_by"),
        instant(rs, "created_at"));
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    return rs.getObject(column, OffsetDateTime.class).toInstant();
  }

  static @Nullable String blankToNull(@Nullable String s) {
    return s == null || s.isBlank() ? null : s;
  }
}
