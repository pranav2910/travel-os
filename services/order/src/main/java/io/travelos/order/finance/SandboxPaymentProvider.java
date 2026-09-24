package io.travelos.order.finance;

import io.travelos.common.money.Money;
import io.travelos.common.tenant.TenantId;
import io.travelos.order.finance.FinanceRecords.Fx;
import io.travelos.order.finance.FinanceRecords.Instrument;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * SIMULATED payment provider. Deterministic, keyed by idempotency key, with its own ledger table so
 * reconciliation has a provider side to read. A token containing {@code declined} is declined; an
 * instrument in another currency is converted with a static table whose provenance is recorded.
 */
@Component
public class SandboxPaymentProvider implements PaymentProvider {
  public static final String PROVIDER = "sandbox-payments";

  /** Units of the currency per one USD; a fixed table, clearly labelled as such in every Fx. */
  static final Map<String, BigDecimal> PER_USD =
      Map.of(
          "USD", BigDecimal.ONE,
          "EUR", new BigDecimal("0.92"),
          "GBP", new BigDecimal("0.79"),
          "CHF", new BigDecimal("0.88"),
          "CAD", new BigDecimal("1.36"),
          "AUD", new BigDecimal("1.52"),
          "JPY", new BigDecimal("150"),
          "INR", new BigDecimal("83"));

  private final JdbcClient jdbc;
  private final Clock clock;

  public SandboxPaymentProvider(JdbcClient jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  @Override
  public String provider() {
    return PROVIDER;
  }

  @Override
  public boolean live() {
    return false;
  }

  @Override
  public Authorization authorize(String key, Instrument instrument, Money amount, String orderId) {
    Optional<Row> existing = byKey(instrument.tenant(), key);
    if (existing.isPresent()) {
      Row r = existing.get();
      return Authorization.approved(r.ref(), fx(r));
    }
    if (instrument.token().toLowerCase().contains("declined")) {
      return Authorization.declined(
          "CARD_DECLINED", "the issuer declined the authorization (sandbox: token says so)");
    }
    if (!instrument.active()) {
      return Authorization.declined("INSTRUMENT_INACTIVE", "the instrument is no longer active");
    }
    Row r =
        record(
            instrument.tenant(),
            "AUTHORIZATION",
            null,
            amount,
            instrument.currency(),
            orderId,
            key);
    return Authorization.approved(r.ref(), fx(r));
  }

  @Override
  public Capture capture(String key, String providerRef, Money amount) {
    Row auth =
        byRef(providerRef)
            .orElseThrow(
                () ->
                    new PaymentException(
                        "AUTHORIZATION_UNKNOWN", "no authorization " + providerRef, false));
    Optional<Row> existing = byKey(TenantId.of(auth.tenant()), key);
    if (existing.isPresent()) {
      return new Capture(existing.get().ref(), amount, fx(existing.get()));
    }
    if (amount.amountMinor() > auth.amountMinor()) {
      throw new PaymentException(
          "CAPTURE_EXCEEDS_AUTHORIZATION",
          amount + " exceeds the authorized " + Money.of(auth.currency(), auth.amountMinor()),
          false);
    }
    Row r =
        record(
            TenantId.of(auth.tenant()),
            "CAPTURE",
            providerRef,
            amount,
            auth.settlementCurrency(),
            auth.orderId(),
            key);
    return new Capture(r.ref(), amount, fx(r));
  }

  @Override
  public void voidAuthorization(String key, String providerRef) {
    Row auth =
        byRef(providerRef)
            .orElseThrow(
                () ->
                    new PaymentException(
                        "AUTHORIZATION_UNKNOWN", "no authorization " + providerRef, false));
    if (byKey(TenantId.of(auth.tenant()), key).isPresent()) {
      return;
    }
    record(
        TenantId.of(auth.tenant()),
        "VOID",
        providerRef,
        Money.of(auth.currency(), auth.amountMinor()),
        auth.settlementCurrency(),
        auth.orderId(),
        key);
  }

  @Override
  public Refund refund(String key, String providerRef, Money amount, String reason) {
    Row parent =
        byRef(providerRef)
            .orElseThrow(
                () -> new PaymentException("PAYMENT_UNKNOWN", "no payment " + providerRef, false));
    Optional<Row> existing = byKey(TenantId.of(parent.tenant()), key);
    if (existing.isPresent()) {
      return new Refund(existing.get().ref(), amount);
    }
    Row r =
        record(
            TenantId.of(parent.tenant()),
            "REFUND",
            providerRef,
            amount,
            parent.settlementCurrency(),
            parent.orderId(),
            key);
    return new Refund(r.ref(), amount);
  }

  @Override
  public List<Transaction> transactions(String tenantId, Instant from, Instant to) {
    return jdbc.sql(
            "SELECT * FROM sandbox_payment_transaction WHERE tenant_id = :t AND at >= :from AND at < :to ORDER BY at")
        .param("t", tenantId)
        .param("from", Timestamp.from(from))
        .param("to", Timestamp.from(to))
        .query(
            (rs, i) ->
                new Transaction(
                    rs.getString("provider_ref"),
                    rs.getString("kind"),
                    Money.of(rs.getString("currency"), rs.getLong("amount_minor")),
                    rs.getString("parent_ref"),
                    rs.getObject("at", OffsetDateTime.class).toInstant()))
        .list();
  }

  // ------------------------------------------------------------------ the sandbox's ledger

  record Row(
      String ref,
      String tenant,
      String kind,
      @Nullable String parentRef,
      String currency,
      long amountMinor,
      String settlementCurrency,
      long settlementMinor,
      @Nullable String orderId,
      Instant at) {}

  private Row record(
      TenantId tenant,
      String kind,
      @Nullable String parentRef,
      Money amount,
      String settlementCurrency,
      @Nullable String orderId,
      String key) {
    String ref = "spx_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    long settlement = convert(amount, settlementCurrency);
    Instant now = clock.instant();
    jdbc.sql(
            """
            INSERT INTO sandbox_payment_transaction (provider_ref, tenant_id, kind, parent_ref, currency, amount_minor,
              settlement_currency, settlement_minor, order_id, idempotency_key, at)
            VALUES (:ref, :t, :kind, :parent, :currency, :minor, :sc, :sm, :order, :key, :at)
            ON CONFLICT ON CONSTRAINT sandbox_payment_idempotency DO NOTHING
            """)
        .param("ref", ref)
        .param("t", tenant.value())
        .param("kind", kind)
        .param("parent", parentRef)
        .param("currency", amount.currency())
        .param("minor", amount.amountMinor())
        .param("sc", settlementCurrency)
        .param("sm", settlement)
        .param("order", orderId)
        .param("key", key)
        .param("at", Timestamp.from(now))
        .update();
    return byKey(tenant, key).orElseThrow();
  }

  static long convert(Money amount, String settlementCurrency) {
    if (amount.currency().equals(settlementCurrency)) {
      return amount.amountMinor();
    }
    BigDecimal from = PER_USD.get(amount.currency());
    BigDecimal to = PER_USD.get(settlementCurrency);
    if (from == null || to == null) {
      throw new PaymentException(
          "CURRENCY_UNSUPPORTED", "sandbox-payments converts only " + PER_USD.keySet(), false);
    }
    return new BigDecimal(amount.amountMinor())
        .multiply(to)
        .divide(from, 0, RoundingMode.HALF_EVEN)
        .longValueExact();
  }

  private static @Nullable Fx fx(Row r) {
    if (r.currency().equals(r.settlementCurrency())) {
      return null;
    }
    BigDecimal rate =
        PER_USD
            .get(r.settlementCurrency())
            .divide(PER_USD.get(r.currency()), 8, RoundingMode.HALF_EVEN);
    return new Fx(
        r.settlementCurrency(), r.settlementMinor(), rate, "sandbox-payments static table", r.at());
  }

  private Optional<Row> byKey(TenantId tenant, String key) {
    return jdbc.sql(
            "SELECT * FROM sandbox_payment_transaction WHERE tenant_id = :t AND idempotency_key = :k")
        .param("t", tenant.value())
        .param("k", key)
        .query(SandboxPaymentProvider::row)
        .optional();
  }

  private Optional<Row> byRef(String ref) {
    return jdbc.sql("SELECT * FROM sandbox_payment_transaction WHERE provider_ref = :r")
        .param("r", ref)
        .query(SandboxPaymentProvider::row)
        .optional();
  }

  private static Row row(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
    return new Row(
        rs.getString("provider_ref"),
        rs.getString("tenant_id"),
        rs.getString("kind"),
        rs.getString("parent_ref"),
        rs.getString("currency"),
        rs.getLong("amount_minor"),
        rs.getString("settlement_currency"),
        rs.getLong("settlement_minor"),
        rs.getString("order_id"),
        rs.getObject("at", OffsetDateTime.class).toInstant());
  }
}
