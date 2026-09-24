package io.travelos.policy.governance;

import io.travelos.common.money.Money;
import io.travelos.policy.governance.GovernanceRecords.Agreement;
import io.travelos.policy.governance.GovernanceRecords.Budget;
import io.travelos.policy.governance.GovernanceRecords.PolicyScope;
import io.travelos.policy.governance.GovernanceRecords.Reservation;
import io.travelos.policy.governance.GovernanceRecords.ScopeKind;
import io.travelos.spring.web.auth.RequestPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Phase 7: scoped policy assignment, budgets and supplier agreements for administrators. */
@RestController
public class GovernanceController {
  private final GovernanceService governance;

  public GovernanceController(GovernanceService governance) {
    this.governance = governance;
  }

  public record ScopeView(
      String scopeId,
      String scopeKind,
      String scopeRef,
      String policyId,
      String createdBy,
      Instant createdAt) {
    static ScopeView from(PolicyScope s) {
      return new ScopeView(
          s.scopeId(), s.kind().name(), s.ref(), s.policyId(), s.createdBy(), s.createdAt());
    }
  }

  public record AssignScopeRequest(
      @NotNull ScopeKind scopeKind,
      @NotBlank @Size(max = 64) String scopeRef,
      @NotBlank @Size(max = 64) String policyId) {}

  @GetMapping(path = "/api/v1/policies/scopes", produces = "application/json")
  public List<ScopeView> scopes(@AuthenticationPrincipal RequestPrincipal me) {
    return governance.scopes(me).stream().map(ScopeView::from).toList();
  }

  @PutMapping(
      path = "/api/v1/policies/scopes",
      consumes = "application/json",
      produces = "application/json")
  public ScopeView assign(
      @AuthenticationPrincipal RequestPrincipal me,
      @Valid @RequestBody AssignScopeRequest request) {
    return ScopeView.from(
        governance.assignScope(me, request.scopeKind(), request.scopeRef(), request.policyId()));
  }

  @DeleteMapping("/api/v1/policies/scopes/{scopeKind}/{scopeRef}")
  public ResponseEntity<Void> unassign(
      @AuthenticationPrincipal RequestPrincipal me,
      @PathVariable ScopeKind scopeKind,
      @PathVariable String scopeRef) {
    governance.unassignScope(me, scopeKind, scopeRef);
    return ResponseEntity.noContent().build();
  }

  public record MoneyView(String currency, long amountMinor) {
    static MoneyView of(Money m) {
      return new MoneyView(m.currency(), m.amountMinor());
    }
  }

  public record BudgetView(
      String budgetId,
      String name,
      String scopeKind,
      String scopeRef,
      Instant periodStart,
      Instant periodEnd,
      MoneyView amount,
      MoneyView reserved,
      MoneyView committed,
      MoneyView remaining,
      boolean hard,
      boolean active,
      long version,
      String createdBy,
      Instant createdAt) {
    static BudgetView from(Budget b) {
      return new BudgetView(
          b.budgetId(),
          b.name(),
          b.kind().name(),
          b.ref(),
          b.periodStart(),
          b.periodEnd(),
          new MoneyView(b.currency(), b.amountMinor()),
          new MoneyView(b.currency(), b.reservedMinor()),
          new MoneyView(b.currency(), b.committedMinor()),
          MoneyView.of(b.remaining()),
          b.hard(),
          b.active(),
          b.version(),
          b.createdBy(),
          b.createdAt());
    }
  }

  public record ReservationView(
      String reservationId,
      String tripId,
      @Nullable String travelerId,
      long amountMinor,
      String status,
      Instant createdAt,
      Instant updatedAt) {
    static ReservationView from(Reservation r) {
      return new ReservationView(
          r.reservationId(),
          r.tripId(),
          r.travelerId(),
          r.amountMinor(),
          r.status().name(),
          r.createdAt(),
          r.updatedAt());
    }
  }

  public record CreateBudgetRequest(
      @NotBlank @Size(max = 200) String name,
      @NotNull ScopeKind scopeKind,
      @NotBlank @Size(max = 64) String scopeRef,
      @NotNull Instant periodStart,
      @NotNull Instant periodEnd,
      @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currency,
      long amountMinor,
      boolean hard) {}

  @GetMapping(path = "/api/v1/budgets", produces = "application/json")
  public List<BudgetView> budgets(@AuthenticationPrincipal RequestPrincipal me) {
    return governance.budgets(me).stream().map(BudgetView::from).toList();
  }

  @PostMapping(
      path = "/api/v1/budgets",
      consumes = "application/json",
      produces = "application/json")
  public ResponseEntity<BudgetView> createBudget(
      @AuthenticationPrincipal RequestPrincipal me,
      @Valid @RequestBody CreateBudgetRequest request) {
    Budget b =
        governance.createBudget(
            me,
            request.name(),
            request.scopeKind(),
            request.scopeRef(),
            request.periodStart(),
            request.periodEnd(),
            Money.of(request.currency(), request.amountMinor()),
            request.hard());
    return ResponseEntity.status(HttpStatus.CREATED).body(BudgetView.from(b));
  }

  @GetMapping(path = "/api/v1/budgets/{budgetId}", produces = "application/json")
  public BudgetView budget(
      @AuthenticationPrincipal RequestPrincipal me, @PathVariable String budgetId) {
    return BudgetView.from(governance.budget(me, budgetId));
  }

  @GetMapping(path = "/api/v1/budgets/{budgetId}/reservations", produces = "application/json")
  public List<ReservationView> reservations(
      @AuthenticationPrincipal RequestPrincipal me, @PathVariable String budgetId) {
    return governance.reservations(me, budgetId).stream().map(ReservationView::from).toList();
  }

  @DeleteMapping("/api/v1/budgets/{budgetId}")
  public ResponseEntity<Void> deactivateBudget(
      @AuthenticationPrincipal RequestPrincipal me, @PathVariable String budgetId) {
    governance.deactivateBudget(me, budgetId);
    return ResponseEntity.noContent().build();
  }

  public record AgreementView(
      String agreementId,
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
    static AgreementView from(Agreement a) {
      return new AgreementView(
          a.agreementId(),
          a.provider(),
          a.kind(),
          a.carrier(),
          a.rateCode(),
          a.preferred(),
          a.negotiated(),
          a.contractRef(),
          a.validFrom(),
          a.validUntil(),
          a.active(),
          a.createdBy(),
          a.createdAt());
    }
  }

  public record CreateAgreementRequest(
      @NotBlank @Size(max = 60) String provider,
      @NotBlank @Size(max = 10) String kind,
      @Nullable @Size(max = 10) String carrier,
      @Nullable @Size(max = 60) String rateCode,
      @Nullable Boolean preferred,
      boolean negotiated,
      @Nullable @Size(max = 200) String contractRef,
      @Nullable Instant validFrom,
      @Nullable Instant validUntil) {}

  @GetMapping(path = "/api/v1/policies/agreements", produces = "application/json")
  public List<AgreementView> agreements(@AuthenticationPrincipal RequestPrincipal me) {
    return governance.agreements(me).stream().map(AgreementView::from).toList();
  }

  @PostMapping(
      path = "/api/v1/policies/agreements",
      consumes = "application/json",
      produces = "application/json")
  public ResponseEntity<AgreementView> createAgreement(
      @AuthenticationPrincipal RequestPrincipal me,
      @Valid @RequestBody CreateAgreementRequest request) {
    Agreement a =
        governance.createAgreement(
            me,
            request.provider(),
            request.kind(),
            request.carrier(),
            request.rateCode(),
            request.preferred() == null || request.preferred(),
            request.negotiated(),
            request.contractRef(),
            request.validFrom(),
            request.validUntil());
    return ResponseEntity.status(HttpStatus.CREATED).body(AgreementView.from(a));
  }

  @DeleteMapping("/api/v1/policies/agreements/{agreementId}")
  public ResponseEntity<Void> endAgreement(
      @AuthenticationPrincipal RequestPrincipal me, @PathVariable String agreementId) {
    governance.endAgreement(me, agreementId);
    return ResponseEntity.noContent().build();
  }
}
