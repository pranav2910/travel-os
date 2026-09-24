package io.travelos.order.finance;

import io.travelos.common.money.Money;
import io.travelos.order.finance.FinanceRecords.Fx;
import io.travelos.order.finance.FinanceRecords.Instrument;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Phase 5 (ADR-0017): the door to a payment provider. Every mutation carries an idempotency key the
 * provider honours (or the provider adapter enforces); amounts are minor units in an explicit
 * currency; conversions are the provider's and come back as {@link Fx} with provenance.
 */
public interface PaymentProvider {

  String provider();

  /** SIMULATED providers say so; LIVE ones move real money. */
  boolean live();

  enum Outcome {
    APPROVED,
    DECLINED
  }

  record Authorization(
      Outcome outcome,
      @Nullable String providerRef,
      @Nullable String reasonCode,
      @Nullable String message,
      @Nullable Fx fx) {
    public static Authorization approved(String ref, @Nullable Fx fx) {
      return new Authorization(Outcome.APPROVED, ref, null, null, fx);
    }

    public static Authorization declined(String code, String message) {
      return new Authorization(Outcome.DECLINED, null, code, message, null);
    }
  }

  record Capture(String providerRef, Money captured, @Nullable Fx fx) {}

  record Refund(String providerRef, Money refunded) {}

  /** One movement the provider recorded, for reconciliation against our ledger. */
  record Transaction(
      String providerRef, String kind, Money amount, @Nullable String parentRef, Instant at) {}

  /** A provider that will not do what was asked (declined, unknown ref); never retried blindly. */
  final class PaymentException extends RuntimeException {
    private final String code;
    private final boolean retryable;

    public PaymentException(String code, String message, boolean retryable) {
      super(message);
      this.code = code;
      this.retryable = retryable;
    }

    public String code() {
      return code;
    }

    public boolean retryable() {
      return retryable;
    }
  }

  Authorization authorize(
      String idempotencyKey, Instrument instrument, Money amount, String orderId);

  Capture capture(String idempotencyKey, String providerRef, Money amount);

  void voidAuthorization(String idempotencyKey, String providerRef);

  Refund refund(String idempotencyKey, String providerRef, Money amount, String reason);

  /** What the provider recorded in the window, newest last; the provider side of reconciliation. */
  List<Transaction> transactions(String tenantId, Instant from, Instant to);
}
