package io.travelos.order.finance;

import io.travelos.common.identity.Principal;
import io.travelos.common.ids.IdPrefix;
import io.travelos.common.ids.Ids;
import io.travelos.common.money.Money;
import io.travelos.common.tenant.TenantId;
import io.travelos.order.finance.FinanceRecords.Credit;
import io.travelos.order.finance.FinanceRecords.CreditStatus;
import io.travelos.order.finance.FinanceRecords.EventKind;
import io.travelos.order.finance.FinanceRecords.Fx;
import io.travelos.order.finance.FinanceRecords.Instrument;
import io.travelos.order.finance.FinanceRecords.InstrumentKind;
import io.travelos.order.finance.FinanceRecords.Payable;
import io.travelos.order.finance.FinanceRecords.PayableStatus;
import io.travelos.order.finance.FinanceRecords.Payment;
import io.travelos.order.finance.FinanceRecords.PaymentEvent;
import io.travelos.order.finance.FinanceRecords.PaymentStatus;
import io.travelos.order.finance.FinanceRecords.SettlementMethod;
import io.travelos.order.store.OrderRecord;
import io.travelos.order.store.OrderRecord.Item;
import io.travelos.spring.outbox.Outbox;
import io.travelos.spring.web.error.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Phase 5 (ADR-0017): the authoritative money records of an order. Every movement is written down
 * with the provider's reference before the order goes on; every call is idempotent by key so a saga
 * that retries after a crash records nothing twice. The platform never converts currencies: the
 * provider does, and what it did is kept as provenance.
 */
@Service
public class FinanceService {
  private static final Logger log = LoggerFactory.getLogger(FinanceService.class);

  private final FinanceRepository finance;
  private final PaymentProviders providers;
  private final FinanceProperties properties;
  private final Outbox outbox;
  private final TransactionTemplate tx;
  private final Clock clock;

  public FinanceService(
      FinanceRepository finance,
      PaymentProviders providers,
      FinanceProperties properties,
      Outbox outbox,
      TransactionTemplate tx,
      Clock clock) {
    this.finance = finance;
    this.providers = providers;
    this.properties = properties;
    this.outbox = outbox;
    this.tx = tx;
    this.clock = clock;
  }

  // ------------------------------------------------------------------ instruments

  /**
   * A tokenized instrument. A card number is refused: tokens come from the provider, never from us.
   */
  public Instrument registerInstrument(
      TenantId tenant,
      Principal by,
      InstrumentKind kind,
      String provider,
      String token,
      @Nullable String label,
      @Nullable String last4,
      String currency,
      @Nullable String ownerEmployeeId) {
    if (looksLikeCardNumber(token)) {
      throw new ApiException.Unprocessable(
          "PAN_NOT_ACCEPTED",
          "that looks like a card number; register the provider's token instead");
    }
    providers.require(provider);
    Instant now = clock.instant();
    Instrument i =
        new Instrument(
            Ids.newId(IdPrefix.PAYMENT_INSTRUMENT),
            tenant,
            kind,
            provider,
            token,
            label,
            last4 == null ? last4(token) : last4,
            currency.toUpperCase(),
            ownerEmployeeId,
            true,
            by.id(),
            now,
            now);
    if (finance.insertInstrument(i) == 0) {
      return finance.instrumentByToken(tenant, token).orElseThrow();
    }
    return i;
  }

  public List<Instrument> instruments(TenantId tenant) {
    return finance.instruments(tenant);
  }

  public void deactivateInstrument(TenantId tenant, String instrumentId) {
    finance
        .instrument(tenant, instrumentId)
        .orElseThrow(() -> new ApiException.NotFound("instrument", instrumentId));
    finance.deactivateInstrument(tenant, instrumentId, clock.instant());
  }

  /**
   * The instrument a payment token names: a registered one, or, for the Slice 1 opaque tokens the
   * workflow still carries, an implicit sandbox instrument (the SIMULATED provider) recorded once.
   */
  Instrument resolveInstrument(TenantId tenant, String token, String currency, Principal by) {
    if (looksLikeCardNumber(token)) {
      throw new PaymentProvider.PaymentException(
          "PAN_NOT_ACCEPTED", "a card number was sent as a payment token", false);
    }
    Optional<Instrument> registered = finance.instrumentByToken(tenant, token);
    if (registered.isPresent()) {
      return registered.get();
    }
    Instant now = clock.instant();
    Instrument implicit =
        new Instrument(
            Ids.newId(IdPrefix.PAYMENT_INSTRUMENT),
            tenant,
            InstrumentKind.CORPORATE_CARD,
            SandboxPaymentProvider.PROVIDER,
            token,
            "Implicit sandbox instrument (opaque token)",
            last4(token),
            currency,
            null,
            true,
            by.id(),
            now,
            now);
    if (finance.insertInstrument(implicit) == 0) {
      return finance.instrumentByToken(tenant, token).orElseThrow();
    }
    return implicit;
  }

  static boolean looksLikeCardNumber(String token) {
    String digits = token.replaceAll("[\\s-]", "");
    if (!digits.matches("\\d{13,19}")) {
      return false;
    }
    int sum = 0;
    boolean alternate = false;
    for (int i = digits.length() - 1; i >= 0; i--) {
      int n = digits.charAt(i) - '0';
      if (alternate) {
        n *= 2;
        if (n > 9) {
          n -= 9;
        }
      }
      sum += n;
      alternate = !alternate;
    }
    return sum % 10 == 0;
  }

  static String last4(String token) {
    String t = token.replaceAll("[^A-Za-z0-9]", "");
    return t.length() <= 4 ? t : t.substring(t.length() - 4);
  }

  // ------------------------------------------------------------------ the order's payment

  /**
   * Authorizes the order total on the instrument before any supplier is called. Idempotent: the
   * order's payment, once there, is returned as it stands. A decline is a recorded DECLINED
   * payment, not an exception: the saga decides what to do (fail before booking).
   */
  public Payment authorize(OrderRecord order, String paymentToken, Principal by) {
    Optional<Payment> existing = finance.paymentOfOrder(order.tenant(), order.orderId());
    if (existing.isPresent()) {
      return existing.get();
    }
    Instrument instrument =
        resolveInstrument(order.tenant(), paymentToken, order.total().currency(), by);
    PaymentProvider provider = providers.require(instrument.provider());
    String key = order.orderId() + ":AUTHORIZE:1";
    Instant now = clock.instant();
    PaymentProvider.Authorization a;
    try {
      a = provider.authorize(key, instrument, order.total(), order.orderId());
    } catch (PaymentProvider.PaymentException e) {
      if (e.retryable()) {
        throw e;
      }
      a = PaymentProvider.Authorization.declined(e.code(), e.getMessage());
    }
    final PaymentProvider.Authorization auth = a;
    boolean approved = auth.outcome() == PaymentProvider.Outcome.APPROVED;
    Payment p =
        new Payment(
            Ids.newId(IdPrefix.PAYMENT),
            order.tenant(),
            order.orderId(),
            order.tripId(),
            instrument.instrumentId(),
            provider.provider(),
            a.providerRef(),
            approved ? PaymentStatus.AUTHORIZED : PaymentStatus.DECLINED,
            order.total().currency(),
            order.total().amountMinor(),
            0,
            0,
            approved ? null : a.reasonCode(),
            approved ? null : a.message(),
            a.fx(),
            now,
            now);
    Payment stored =
        tx.execute(
            s -> {
              if (finance.insertPayment(p) == 0) {
                return finance.paymentOfOrder(order.tenant(), order.orderId()).orElseThrow();
              }
              finance.insertEvent(
                  new PaymentEvent(
                      Ids.newId(IdPrefix.PAYMENT_EVENT),
                      order.tenant(),
                      p.paymentId(),
                      approved ? EventKind.AUTHORIZE : EventKind.DECLINE,
                      key,
                      order.total(),
                      auth.providerRef(),
                      approved,
                      approved ? null : auth.reasonCode() + ": " + auth.message(),
                      null,
                      now));
              if (approved) {
                outbox.append(
                    FinanceEvents.payment("travel.finance.payment-authorized", p, Map.of(), clock));
              } else {
                Map<String, Object> extra = new LinkedHashMap<>();
                extra.put("reasonCode", auth.reasonCode() == null ? "DECLINED" : auth.reasonCode());
                extra.put("message", auth.message() == null ? "" : auth.message());
                outbox.append(
                    FinanceEvents.payment("travel.finance.payment-declined", p, extra, clock));
              }
              return p;
            });
    log.info(
        "order {}: payment {} {} on {} ({})",
        order.orderId(),
        stored.paymentId(),
        stored.status(),
        instrument,
        provider.provider());
    return stored;
  }

  /** Captures what the suppliers charged, up to the authorization. Idempotent by order. */
  public Payment capture(OrderRecord order, Money amount) {
    Payment p =
        finance
            .paymentOfOrder(order.tenant(), order.orderId())
            .orElseThrow(
                () -> new IllegalStateException("order " + order.orderId() + " has no payment"));
    if (p.status() != PaymentStatus.AUTHORIZED) {
      return p;
    }
    String key = order.orderId() + ":CAPTURE:1";
    Money toCapture = amount.amountMinor() > p.authorizedMinor() ? p.authorized() : amount;
    PaymentProvider.Capture c =
        providers.require(p.provider()).capture(key, p.providerRef(), toCapture);
    Instant now = clock.instant();
    Payment captured =
        new Payment(
            p.paymentId(),
            p.tenant(),
            p.orderId(),
            p.tripId(),
            p.instrumentId(),
            p.provider(),
            p.providerRef(),
            PaymentStatus.CAPTURED,
            p.currency(),
            p.authorizedMinor(),
            toCapture.amountMinor(),
            0,
            null,
            null,
            c.fx() == null ? p.fx() : c.fx(),
            p.createdAt(),
            now);
    tx.executeWithoutResult(
        s -> {
          finance.updatePayment(captured);
          finance.insertEvent(
              new PaymentEvent(
                  Ids.newId(IdPrefix.PAYMENT_EVENT),
                  p.tenant(),
                  p.paymentId(),
                  EventKind.CAPTURE,
                  key,
                  toCapture,
                  c.providerRef(),
                  true,
                  null,
                  null,
                  now));
          outbox.append(
              FinanceEvents.payment(
                  "travel.finance.payment-captured",
                  captured,
                  Map.of("capturedTotal", FinanceEvents.money(toCapture)),
                  clock));
        });
    return captured;
  }

  /** Nothing was booked (or everything was released): the authorization is voided. */
  public Optional<Payment> release(OrderRecord order, String reason) {
    Optional<Payment> existing = finance.paymentOfOrder(order.tenant(), order.orderId());
    if (existing.isEmpty()) {
      return Optional.empty();
    }
    Payment p = existing.get();
    if (p.status() != PaymentStatus.AUTHORIZED) {
      return existing;
    }
    String key = order.orderId() + ":VOID:1";
    providers.require(p.provider()).voidAuthorization(key, p.providerRef());
    Instant now = clock.instant();
    Payment voided =
        new Payment(
            p.paymentId(),
            p.tenant(),
            p.orderId(),
            p.tripId(),
            p.instrumentId(),
            p.provider(),
            p.providerRef(),
            PaymentStatus.VOIDED,
            p.currency(),
            p.authorizedMinor(),
            0,
            0,
            null,
            null,
            p.fx(),
            p.createdAt(),
            now);
    tx.executeWithoutResult(
        s -> {
          finance.updatePayment(voided);
          finance.insertEvent(
              new PaymentEvent(
                  Ids.newId(IdPrefix.PAYMENT_EVENT),
                  p.tenant(),
                  p.paymentId(),
                  EventKind.VOID,
                  key,
                  p.authorized(),
                  p.providerRef(),
                  true,
                  reason,
                  null,
                  now));
          outbox.append(
              FinanceEvents.payment(
                  "travel.finance.payment-voided", voided, Map.of("reason", reason), clock));
        });
    return Optional.of(voided);
  }

  /**
   * Money back for one item (a supplier refund on release, a cheaper change). Idempotent per item
   * and attempt; never more than what was captured.
   */
  public Optional<Payment> refund(
      OrderRecord order, @Nullable String itemId, Money amount, String reason, String attempt) {
    if (amount.amountMinor() <= 0) {
      return finance.paymentOfOrder(order.tenant(), order.orderId());
    }
    Optional<Payment> existing = finance.paymentOfOrder(order.tenant(), order.orderId());
    if (existing.isEmpty()) {
      return Optional.empty();
    }
    Payment p = existing.get();
    if (p.status() != PaymentStatus.CAPTURED && p.status() != PaymentStatus.PARTIALLY_REFUNDED) {
      log.warn(
          "order {}: refund of {} requested while the payment is {}; recorded as an event only",
          order.orderId(),
          amount,
          p.status());
    }
    String key = order.orderId() + ":REFUND:" + (itemId == null ? "order" : itemId) + ":" + attempt;
    if (finance.eventByKey(order.tenant(), key).isPresent()) {
      return existing;
    }
    if (!amount.currency().equals(p.currency())) {
      throw new PaymentProvider.PaymentException(
          "CURRENCY_MISMATCH",
          "a refund in " + amount.currency() + " against a " + p.currency() + " payment",
          false);
    }
    long remaining = p.capturedMinor() - p.refundedMinor();
    Money toRefund =
        amount.amountMinor() > remaining ? Money.of(p.currency(), Math.max(0, remaining)) : amount;
    if (toRefund.amountMinor() <= 0) {
      return existing;
    }
    PaymentProvider.Refund r =
        providers.require(p.provider()).refund(key, p.providerRef(), toRefund, reason);
    Instant now = clock.instant();
    long refunded = p.refundedMinor() + toRefund.amountMinor();
    Payment updated =
        new Payment(
            p.paymentId(),
            p.tenant(),
            p.orderId(),
            p.tripId(),
            p.instrumentId(),
            p.provider(),
            p.providerRef(),
            refunded >= p.capturedMinor()
                ? PaymentStatus.REFUNDED
                : PaymentStatus.PARTIALLY_REFUNDED,
            p.currency(),
            p.authorizedMinor(),
            p.capturedMinor(),
            refunded,
            null,
            null,
            p.fx(),
            p.createdAt(),
            now);
    tx.executeWithoutResult(
        s -> {
          finance.updatePayment(updated);
          finance.insertEvent(
              new PaymentEvent(
                  Ids.newId(IdPrefix.PAYMENT_EVENT),
                  p.tenant(),
                  p.paymentId(),
                  EventKind.REFUND,
                  key,
                  toRefund,
                  r.providerRef(),
                  true,
                  reason,
                  itemId,
                  now));
          Map<String, Object> extra = new LinkedHashMap<>();
          extra.put("refundedTotal", FinanceEvents.money(Money.of(p.currency(), refunded)));
          if (itemId != null) {
            extra.put("itemId", itemId);
          }
          extra.put("reason", reason);
          outbox.append(
              FinanceEvents.payment("travel.finance.payment-refunded", updated, extra, clock));
        });
    return Optional.of(updated);
  }

  /** A change that costs more: a second authorization + capture for the difference. */
  public Optional<Payment> captureAdditional(
      OrderRecord order, Money delta, String changeId, String paymentToken, Principal by) {
    Optional<Payment> existing = finance.paymentOfOrder(order.tenant(), order.orderId());
    if (existing.isEmpty() || delta.amountMinor() <= 0) {
      return existing;
    }
    Payment p = existing.get();
    String key = order.orderId() + ":CHANGE:" + changeId;
    if (finance.eventByKey(order.tenant(), key).isPresent()) {
      return existing;
    }
    Instrument instrument = finance.instrument(order.tenant(), p.instrumentId()).orElseThrow();
    PaymentProvider provider = providers.require(p.provider());
    PaymentProvider.Authorization a =
        provider.authorize(key + ":AUTH", instrument, delta, order.orderId());
    if (a.outcome() != PaymentProvider.Outcome.APPROVED) {
      throw new PaymentProvider.PaymentException(
          a.reasonCode() == null ? "DECLINED" : a.reasonCode(),
          a.message() == null ? "the incremental charge was declined" : a.message(),
          false);
    }
    PaymentProvider.Capture c = provider.capture(key, a.providerRef(), delta);
    Instant now = clock.instant();
    Payment updated =
        new Payment(
            p.paymentId(),
            p.tenant(),
            p.orderId(),
            p.tripId(),
            p.instrumentId(),
            p.provider(),
            p.providerRef(),
            p.status() == PaymentStatus.AUTHORIZED
                ? PaymentStatus.AUTHORIZED
                : PaymentStatus.CAPTURED,
            p.currency(),
            p.authorizedMinor() + delta.amountMinor(),
            p.capturedMinor() + delta.amountMinor(),
            p.refundedMinor(),
            null,
            null,
            p.fx(),
            p.createdAt(),
            now);
    tx.executeWithoutResult(
        s -> {
          finance.updatePayment(updated);
          finance.insertEvent(
              new PaymentEvent(
                  Ids.newId(IdPrefix.PAYMENT_EVENT),
                  p.tenant(),
                  p.paymentId(),
                  EventKind.CAPTURE,
                  key,
                  delta,
                  c.providerRef(),
                  true,
                  "change " + changeId,
                  null,
                  now));
          outbox.append(
              FinanceEvents.payment(
                  "travel.finance.payment-captured",
                  updated,
                  Map.of("capturedTotal", FinanceEvents.money(updated.captured())),
                  clock));
        });
    return Optional.of(updated);
  }

  public Optional<Payment> paymentOf(TenantId tenant, String orderId) {
    return finance.paymentOfOrder(tenant, orderId);
  }

  // ------------------------------------------------------------------ payables

  /**
   * What the platform owes the supplier for a confirmed item, by the provider's settlement method.
   */
  public Payable recordPayable(
      OrderRecord order, Item item, Money charged, @Nullable String externalRef) {
    SettlementMethod method = properties.methodFor(item.provider());
    Instant now = clock.instant();
    Payable p =
        new Payable(
            Ids.newId(IdPrefix.PAYABLE),
            order.tenant(),
            order.orderId(),
            item.itemId(),
            item.provider(),
            externalRef,
            charged,
            method,
            method == SettlementMethod.CARD_AT_SUPPLIER ? PayableStatus.SETTLED : PayableStatus.DUE,
            null,
            null,
            method == SettlementMethod.CARD_AT_SUPPLIER ? now : null,
            now,
            now);
    tx.executeWithoutResult(
        s -> {
          if (finance.insertPayable(p) == 1) {
            outbox.append(FinanceEvents.payableRecorded(p, order.tripId(), clock));
          }
        });
    return p;
  }

  public List<Payable> payables(
      TenantId tenant, @Nullable String provider, @Nullable PayableStatus status, int limit) {
    return finance.payables(tenant, provider, status, limit);
  }

  /** Finance marks what was invoiced or paid; card-settled payables need no action. */
  public Payable settlePayable(
      TenantId tenant,
      Principal by,
      String payableId,
      PayableStatus status,
      @Nullable String invoiceReference,
      String tripId) {
    if (status != PayableStatus.INVOICED && status != PayableStatus.PAID) {
      throw new ApiException.Unprocessable(
          "STATUS_INVALID", "a payable is settled as INVOICED or PAID");
    }
    Payable p =
        finance
            .payable(tenant, payableId)
            .orElseThrow(() -> new ApiException.NotFound("payable", payableId));
    if (p.status() == status) {
      return p;
    }
    return tx.execute(
        s -> {
          if (!finance.settlePayable(
              tenant, payableId, status, invoiceReference, by.id(), clock.instant())) {
            throw new ApiException.Conflict(
                "PAYABLE_NOT_OPEN", "payable " + payableId + " is " + p.status());
          }
          Payable settled = finance.payable(tenant, payableId).orElseThrow();
          outbox.append(FinanceEvents.payableSettled(settled, tripId, by, clock));
          return settled;
        });
  }

  /** Owed per provider: DUE and INVOICED payables summed by currency. */
  public Map<String, Map<String, Long>> balances(TenantId tenant) {
    Map<String, Map<String, Long>> out = new LinkedHashMap<>();
    for (Payable p : finance.payables(tenant, null, null, 10_000)) {
      if (p.status() == PayableStatus.DUE || p.status() == PayableStatus.INVOICED) {
        out.computeIfAbsent(p.provider(), k -> new LinkedHashMap<>())
            .merge(p.amount().currency(), p.amount().amountMinor(), Long::sum);
      }
    }
    return out;
  }

  // ------------------------------------------------------------------ credits

  /** A supplier kept value for future travel instead of money back. Not money moved. */
  public Optional<Credit> issueCredit(
      OrderRecord order, Item item, Money amount, String reference, @Nullable Instant expiresAt) {
    if (amount.amountMinor() <= 0) {
      return Optional.empty();
    }
    Instant now = clock.instant();
    Credit c =
        new Credit(
            Ids.newId(IdPrefix.TRAVEL_CREDIT),
            order.tenant(),
            order.travelerId(),
            item.provider(),
            reference,
            order.orderId(),
            item.itemId(),
            amount,
            CreditStatus.AVAILABLE,
            expiresAt,
            null,
            null,
            null,
            null,
            now,
            now);
    return tx.execute(
        s -> {
          if (finance.insertCredit(c) == 0) {
            return Optional.of(
                finance.creditsOfOrder(order.tenant(), order.orderId()).stream()
                    .filter(x -> x.reference().equals(reference))
                    .findFirst()
                    .orElse(c));
          }
          finance.recordItemCredit(item.itemId(), amount, reference, now);
          outbox.append(
              FinanceEvents.credit("travel.finance.credit-issued", c, order.tripId(), null, clock));
          return Optional.of(c);
        });
  }

  public List<Credit> credits(
      TenantId tenant, @Nullable String travelerId, @Nullable CreditStatus status, int limit) {
    return finance.credits(tenant, travelerId, status, limit);
  }

  /** Finance records that the supplier applied the credit to another booking. */
  public Credit applyCredit(
      TenantId tenant, Principal by, String creditId, String orderId, @Nullable String note) {
    Credit c =
        finance
            .credit(tenant, creditId)
            .orElseThrow(() -> new ApiException.NotFound("credit", creditId));
    if (c.status() == CreditStatus.APPLIED && orderId.equals(c.appliedToOrderId())) {
      return c;
    }
    return tx.execute(
        s -> {
          if (!finance.applyCredit(tenant, creditId, orderId, by.id(), note, clock.instant())) {
            throw new ApiException.Conflict(
                "CREDIT_NOT_AVAILABLE", "credit " + creditId + " is " + c.status());
          }
          Credit applied = finance.credit(tenant, creditId).orElseThrow();
          outbox.append(
              FinanceEvents.credit(
                  "travel.finance.credit-applied",
                  applied,
                  c.orderId() == null ? creditId : c.orderId(),
                  by,
                  clock));
          return applied;
        });
  }

  @Scheduled(fixedDelayString = "${travelos.finance.credit-sweep:1h}")
  public void expireCredits() {
    expireCreditsNow(clock.instant());
  }

  public int expireCreditsNow(Instant now) {
    return tx.execute(
        s -> {
          List<Credit> expired = finance.expireCredits(now);
          for (Credit c : expired) {
            outbox.append(
                FinanceEvents.credit(
                    "travel.finance.credit-expired",
                    c,
                    c.orderId() == null ? c.creditId() : c.orderId(),
                    null,
                    clock));
          }
          return expired.size();
        });
  }

  // ------------------------------------------------------------------ receipts & reconciliation

  public record Receipt(
      String orderId,
      String tripId,
      String travelerId,
      String status,
      Money total,
      @Nullable Payment payment,
      List<PaymentEvent> paymentEvents,
      @Nullable Instrument instrument,
      List<Payable> payables,
      List<Credit> credits,
      Instant issuedAt) {}

  public Receipt receipt(OrderRecord order) {
    Optional<Payment> payment = finance.paymentOfOrder(order.tenant(), order.orderId());
    return new Receipt(
        order.orderId(),
        order.tripId(),
        order.travelerId(),
        order.status().name(),
        order.total(),
        payment.orElse(null),
        payment.map(p -> finance.events(order.tenant(), p.paymentId())).orElse(List.of()),
        payment.flatMap(p -> finance.instrument(order.tenant(), p.instrumentId())).orElse(null),
        finance.payablesOfOrder(order.tenant(), order.orderId()),
        finance.creditsOfOrder(order.tenant(), order.orderId()),
        clock.instant());
  }

  public enum Match {
    MATCHED,
    AMOUNT_MISMATCH,
    MISSING_AT_PROVIDER,
    MISSING_LOCALLY
  }

  public record ReconciliationLine(
      String provider,
      String providerRef,
      String kind,
      @Nullable Money local,
      @Nullable Money atProvider,
      Match match,
      @Nullable String paymentId) {}

  public record Reconciliation(
      Instant from,
      Instant to,
      int matched,
      int mismatched,
      int missingAtProvider,
      int missingLocally,
      List<ReconciliationLine> lines) {}

  /**
   * Our payment events against what each provider recorded in the window, by provider reference.
   */
  public Reconciliation reconcile(TenantId tenant, Instant from, Instant to) {
    List<PaymentEvent> local =
        finance.events(tenant, from, to).stream().filter(PaymentEvent::succeeded).toList();
    Map<String, PaymentEvent> localByRef = new LinkedHashMap<>();
    Map<String, String> providerOfPayment = new LinkedHashMap<>();
    for (Payment p : finance.payments(tenant, from.minusSeconds(86_400 * 90), to)) {
      providerOfPayment.put(p.paymentId(), p.provider());
    }
    for (PaymentEvent e : local) {
      if (e.providerRef() != null && e.kind() != EventKind.DECLINE) {
        localByRef.put(
            providerOfPayment.getOrDefault(e.paymentId(), "?") + "|" + e.providerRef(), e);
      }
    }
    List<ReconciliationLine> lines = new ArrayList<>();
    int matched = 0, mismatched = 0, missingAtProvider = 0, missingLocally = 0;
    for (String providerId : providers.providers()) {
      List<PaymentProvider.Transaction> remote;
      try {
        remote = providers.require(providerId).transactions(tenant.value(), from, to);
      } catch (RuntimeException e) {
        log.warn("reconciliation: {} could not be read ({})", providerId, e.getMessage());
        continue;
      }
      java.util.Set<String> seen = new java.util.HashSet<>();
      for (PaymentProvider.Transaction t : remote) {
        String key = providerId + "|" + t.providerRef();
        seen.add(key);
        PaymentEvent mine = localByRef.get(key);
        if (mine == null) {
          missingLocally++;
          lines.add(
              new ReconciliationLine(
                  providerId,
                  t.providerRef(),
                  t.kind(),
                  null,
                  t.amount(),
                  Match.MISSING_LOCALLY,
                  null));
        } else if (mine.amount().equals(t.amount())) {
          matched++;
          lines.add(
              new ReconciliationLine(
                  providerId,
                  t.providerRef(),
                  t.kind(),
                  mine.amount(),
                  t.amount(),
                  Match.MATCHED,
                  mine.paymentId()));
        } else {
          mismatched++;
          lines.add(
              new ReconciliationLine(
                  providerId,
                  t.providerRef(),
                  t.kind(),
                  mine.amount(),
                  t.amount(),
                  Match.AMOUNT_MISMATCH,
                  mine.paymentId()));
        }
      }
      for (Map.Entry<String, PaymentEvent> e : localByRef.entrySet()) {
        if (e.getKey().startsWith(providerId + "|") && !seen.contains(e.getKey())) {
          missingAtProvider++;
          lines.add(
              new ReconciliationLine(
                  providerId,
                  e.getValue().providerRef(),
                  e.getValue().kind().name(),
                  e.getValue().amount(),
                  null,
                  Match.MISSING_AT_PROVIDER,
                  e.getValue().paymentId()));
        }
      }
    }
    return new Reconciliation(
        from, to, matched, mismatched, missingAtProvider, missingLocally, lines);
  }

  @Nullable Fx fxOf(Payment p) {
    return p.fx();
  }
}
