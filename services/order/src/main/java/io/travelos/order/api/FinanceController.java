package io.travelos.order.api;

import io.travelos.common.money.Money;
import io.travelos.order.finance.FinanceRecords;
import io.travelos.order.finance.FinanceRecords.Credit;
import io.travelos.order.finance.FinanceRecords.Instrument;
import io.travelos.order.finance.FinanceRecords.Payable;
import io.travelos.order.finance.FinanceService;
import io.travelos.spring.web.auth.RequestPrincipal;
import io.travelos.spring.web.error.ApiException;
import io.travelos.spring.web.idempotency.IdempotencyKeyHeader;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 5 (ADR-0017): the finance ledger for people. Instruments and supplier payables are Finance
 * and travel-admin matters; a traveler sees their own credits.
 */
@RestController
@RequestMapping(path = "/api/v1/finance", produces = "application/json")
public class FinanceController {
  private final FinanceService finance;

  public FinanceController(FinanceService finance) {
    this.finance = finance;
  }

  public record MoneyView(String currency, long amountMinor, String display) {
    static MoneyView of(Money m) {
      return new MoneyView(m.currency(), m.amountMinor(), m.toString());
    }
  }

  // ------------------------------------------------------------------ instruments

  public record InstrumentRequest(
      @NotNull FinanceRecords.InstrumentKind kind,
      @NotBlank @Size(max = 40) String provider,
      @NotBlank @Size(max = 200) String token,
      @Nullable @Size(max = 120) String label,
      @Nullable @Pattern(regexp = "^[0-9A-Za-z]{2,4}$") String last4,
      @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currency,
      @Nullable @Size(max = 64) String ownerEmployeeId) {}

  public record InstrumentView(
      String instrumentId,
      String kind,
      String provider,
      @Nullable String label,
      @Nullable String last4,
      String currency,
      @Nullable String ownerEmployeeId,
      boolean active,
      Instant createdAt) {
    static InstrumentView from(Instrument i) {
      return new InstrumentView(
          i.instrumentId(),
          i.kind().name(),
          i.provider(),
          i.label(),
          i.last4(),
          i.currency(),
          i.ownerEmployeeId(),
          i.active(),
          i.createdAt());
    }
  }

  @GetMapping("/instruments")
  public List<InstrumentView> instruments(@AuthenticationPrincipal RequestPrincipal me) {
    requireFinance(me);
    return finance.instruments(me.tenant()).stream().map(InstrumentView::from).toList();
  }

  /**
   * The token is the provider's; a card number is refused (422 PAN_NOT_ACCEPTED) and never stored.
   */
  @PostMapping(path = "/instruments", consumes = "application/json")
  @ResponseStatus(HttpStatus.CREATED)
  public InstrumentView register(
      @AuthenticationPrincipal RequestPrincipal me, @Valid @RequestBody InstrumentRequest r) {
    requireFinance(me);
    return InstrumentView.from(
        finance.registerInstrument(
            me.tenant(),
            me.principal(),
            r.kind(),
            r.provider(),
            r.token(),
            r.label(),
            r.last4(),
            r.currency(),
            r.ownerEmployeeId()));
  }

  @DeleteMapping("/instruments/{instrumentId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deactivate(
      @AuthenticationPrincipal RequestPrincipal me, @PathVariable String instrumentId) {
    requireFinance(me);
    finance.deactivateInstrument(me.tenant(), instrumentId);
  }

  // ------------------------------------------------------------------ payables

  public record PayableView(
      String payableId,
      String orderId,
      String itemId,
      String provider,
      @Nullable String externalRef,
      MoneyView amount,
      String method,
      String status,
      @Nullable String invoiceReference,
      @Nullable String settledBy,
      @Nullable Instant settledAt,
      Instant createdAt) {
    static PayableView from(Payable p) {
      return new PayableView(
          p.payableId(),
          p.orderId(),
          p.itemId(),
          p.provider(),
          p.externalRef(),
          MoneyView.of(p.amount()),
          p.method().name(),
          p.status().name(),
          p.invoiceReference(),
          p.settledBy(),
          p.settledAt(),
          p.createdAt());
    }
  }

  @GetMapping("/payables")
  public List<PayableView> payables(
      @AuthenticationPrincipal RequestPrincipal me,
      @RequestParam(required = false) @Nullable String provider,
      @RequestParam(required = false) @Nullable String status,
      @RequestParam(defaultValue = "200") int limit) {
    requireFinance(me);
    FinanceRecords.PayableStatus s = null;
    if (status != null && !status.isBlank()) {
      try {
        s = FinanceRecords.PayableStatus.valueOf(status);
      } catch (IllegalArgumentException e) {
        throw new ApiException.Unprocessable("STATUS_UNKNOWN", "unknown status " + status);
      }
    }
    return finance.payables(me.tenant(), provider, s, Math.max(1, Math.min(limit, 1000))).stream()
        .map(PayableView::from)
        .toList();
  }

  /** What is owed per supplier and currency (DUE + INVOICED), in minor units. */
  @GetMapping("/balances")
  public Map<String, Map<String, Long>> balances(@AuthenticationPrincipal RequestPrincipal me) {
    requireFinance(me);
    return finance.balances(me.tenant());
  }

  public record SettlementRequest(
      @NotNull FinanceRecords.PayableStatus status,
      @Nullable @Size(max = 120) String invoiceReference,
      @Nullable @Size(max = 64) String tripId) {}

  @PostMapping(path = "/payables/{payableId}/settlement", consumes = "application/json")
  public PayableView settle(
      @AuthenticationPrincipal RequestPrincipal me,
      @PathVariable String payableId,
      @IdempotencyKeyHeader String idempotencyKey,
      @Valid @RequestBody SettlementRequest r) {
    if (!me.hasRole("FINANCE")) {
      throw new ApiException.Forbidden(
          "NOT_FINANCE", "only Finance settles what is owed to a supplier");
    }
    return PayableView.from(
        finance.settlePayable(
            me.tenant(),
            me.principal(),
            payableId,
            r.status(),
            r.invoiceReference(),
            r.tripId() == null ? payableId : r.tripId()));
  }

  // ------------------------------------------------------------------ credits

  public record CreditView(
      String creditId,
      String travelerId,
      String provider,
      String reference,
      @Nullable String orderId,
      @Nullable String itemId,
      MoneyView amount,
      String status,
      @Nullable Instant expiresAt,
      @Nullable String appliedToOrderId,
      @Nullable String appliedBy,
      @Nullable Instant appliedAt,
      @Nullable String note) {
    static CreditView from(Credit c) {
      return new CreditView(
          c.creditId(),
          c.travelerId(),
          c.provider(),
          c.reference(),
          c.orderId(),
          c.itemId(),
          MoneyView.of(c.amount()),
          c.status().name(),
          c.expiresAt(),
          c.appliedToOrderId(),
          c.appliedBy(),
          c.appliedAt(),
          c.note());
    }
  }

  /** A traveler's own credits; Finance and travel admins see the tenant's (filter by traveler). */
  @GetMapping("/credits")
  public List<CreditView> credits(
      @AuthenticationPrincipal RequestPrincipal me,
      @RequestParam(required = false) @Nullable String travelerId,
      @RequestParam(required = false) @Nullable String status,
      @RequestParam(defaultValue = "200") int limit) {
    String traveler =
        me.hasAnyRole("FINANCE", "TRAVEL_ADMIN") ? travelerId : me.employeeIdOrThrow();
    FinanceRecords.CreditStatus s = null;
    if (status != null && !status.isBlank()) {
      try {
        s = FinanceRecords.CreditStatus.valueOf(status);
      } catch (IllegalArgumentException e) {
        throw new ApiException.Unprocessable("STATUS_UNKNOWN", "unknown status " + status);
      }
    }
    return finance.credits(me.tenant(), traveler, s, Math.max(1, Math.min(limit, 1000))).stream()
        .map(CreditView::from)
        .toList();
  }

  public record ApplicationRequest(
      @NotBlank @Size(max = 64) String orderId, @Nullable @Size(max = 500) String note) {}

  /** Finance records that the supplier applied a credit to another booking; it moves no money. */
  @PostMapping(path = "/credits/{creditId}/application", consumes = "application/json")
  public CreditView apply(
      @AuthenticationPrincipal RequestPrincipal me,
      @PathVariable String creditId,
      @IdempotencyKeyHeader String idempotencyKey,
      @Valid @RequestBody ApplicationRequest r) {
    if (!me.hasRole("FINANCE")) {
      throw new ApiException.Forbidden("NOT_FINANCE", "only Finance records an applied credit");
    }
    return CreditView.from(
        finance.applyCredit(me.tenant(), me.principal(), creditId, r.orderId(), r.note()));
  }

  // ------------------------------------------------------------------ reconciliation

  public record LineView(
      String provider,
      String providerRef,
      String kind,
      @Nullable MoneyView local,
      @Nullable MoneyView atProvider,
      String match,
      @Nullable String paymentId) {}

  public record ReconciliationView(
      Instant from,
      Instant to,
      int matched,
      int mismatched,
      int missingAtProvider,
      int missingLocally,
      List<LineView> lines) {}

  @GetMapping("/reconciliation")
  public ReconciliationView reconcile(
      @AuthenticationPrincipal RequestPrincipal me,
      @RequestParam Instant from,
      @RequestParam Instant to) {
    if (!me.hasRole("FINANCE")) {
      throw new ApiException.Forbidden("NOT_FINANCE", "only Finance reconciles");
    }
    if (!to.isAfter(from) || to.minusSeconds(86_400L * 92).isAfter(from)) {
      throw new ApiException.Unprocessable("WINDOW_INVALID", "from < to, at most 92 days apart");
    }
    FinanceService.Reconciliation r = finance.reconcile(me.tenant(), from, to);
    return new ReconciliationView(
        r.from(),
        r.to(),
        r.matched(),
        r.mismatched(),
        r.missingAtProvider(),
        r.missingLocally(),
        r.lines().stream()
            .map(
                l ->
                    new LineView(
                        l.provider(),
                        l.providerRef(),
                        l.kind(),
                        l.local() == null ? null : MoneyView.of(l.local()),
                        l.atProvider() == null ? null : MoneyView.of(l.atProvider()),
                        l.match().name(),
                        l.paymentId()))
            .toList());
  }

  private static void requireFinance(RequestPrincipal me) {
    if (!me.hasAnyRole("FINANCE", "TRAVEL_ADMIN")) {
      throw new ApiException.Forbidden(
          "NOT_FINANCE", "instruments and payables are a Finance / travel-admin matter");
    }
  }
}
