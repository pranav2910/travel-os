package io.travelos.order.finance;

import io.travelos.common.money.Money;
import io.travelos.common.tenant.TenantId;
import java.math.BigDecimal;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** The finance ledger's rows (Phase 5, ADR-0017). */
public final class FinanceRecords {
  private FinanceRecords() {}

  public enum InstrumentKind {
    CORPORATE_CARD,
    VIRTUAL_CARD,
    CENTRAL_BILL
  }

  /** A tokenized way to pay; the token is the provider's, never a card number. */
  public record Instrument(
      String instrumentId,
      TenantId tenant,
      InstrumentKind kind,
      String provider,
      String token,
      @Nullable String label,
      @Nullable String last4,
      String currency,
      @Nullable String ownerEmployeeId,
      boolean active,
      String createdBy,
      Instant createdAt,
      Instant updatedAt) {
    @Override
    public String toString() {
      return "Instrument[" + instrumentId + " " + kind + " " + provider + " ****" + last4 + "]";
    }
  }

  public enum PaymentStatus {
    AUTHORIZED,
    CAPTURED,
    VOIDED,
    PARTIALLY_REFUNDED,
    REFUNDED,
    DECLINED,
    FAILED
  }

  /** The provider's conversion, recorded with its provenance; never computed by the platform. */
  public record Fx(
      String settlementCurrency,
      long settlementMinor,
      BigDecimal rate,
      String source,
      Instant quotedAt) {}

  public record Payment(
      String paymentId,
      TenantId tenant,
      String orderId,
      String tripId,
      String instrumentId,
      String provider,
      @Nullable String providerRef,
      PaymentStatus status,
      String currency,
      long authorizedMinor,
      long capturedMinor,
      long refundedMinor,
      @Nullable String failureCode,
      @Nullable String failureMessage,
      @Nullable Fx fx,
      Instant createdAt,
      Instant updatedAt) {
    public Money authorized() {
      return Money.of(currency, authorizedMinor);
    }

    public Money captured() {
      return Money.of(currency, capturedMinor);
    }

    public Money refunded() {
      return Money.of(currency, refundedMinor);
    }
  }

  public enum EventKind {
    AUTHORIZE,
    CAPTURE,
    VOID,
    REFUND,
    DECLINE
  }

  public record PaymentEvent(
      String eventId,
      TenantId tenant,
      String paymentId,
      EventKind kind,
      String idempotencyKey,
      Money amount,
      @Nullable String providerRef,
      boolean succeeded,
      @Nullable String detail,
      @Nullable String itemId,
      Instant occurredAt) {}

  public enum SettlementMethod {
    /** The supplier charged the instrument itself; nothing is owed to it by the platform. */
    CARD_AT_SUPPLIER,
    /** Debited from a prepaid balance at the supplier (Duffel); the balance is what is owed. */
    BALANCE,
    /** The supplier invoices; owed until paid. */
    INVOICE
  }

  public enum PayableStatus {
    SETTLED,
    DUE,
    INVOICED,
    PAID
  }

  public record Payable(
      String payableId,
      TenantId tenant,
      String orderId,
      String itemId,
      String provider,
      @Nullable String externalRef,
      Money amount,
      SettlementMethod method,
      PayableStatus status,
      @Nullable String invoiceReference,
      @Nullable String settledBy,
      @Nullable Instant settledAt,
      Instant createdAt,
      Instant updatedAt) {}

  public enum CreditStatus {
    AVAILABLE,
    APPLIED,
    EXPIRED,
    VOID
  }

  /** Value a supplier keeps for future travel instead of money back. Not money moved. */
  public record Credit(
      String creditId,
      TenantId tenant,
      String travelerId,
      String provider,
      String reference,
      @Nullable String orderId,
      @Nullable String itemId,
      Money amount,
      CreditStatus status,
      @Nullable Instant expiresAt,
      @Nullable String appliedToOrderId,
      @Nullable String appliedBy,
      @Nullable Instant appliedAt,
      @Nullable String note,
      Instant createdAt,
      Instant updatedAt) {}
}
