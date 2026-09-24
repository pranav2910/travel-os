package io.travelos.order.finance;

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

/** The finance tables of the Order service, one repository: they change together. */
@Repository
public class FinanceRepository {
  private final JdbcClient jdbc;

  public FinanceRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  // ------------------------------------------------------------------ instruments

  public int insertInstrument(Instrument i) {
    return jdbc.sql(
            """
            INSERT INTO payment_instrument (instrument_id, tenant_id, kind, provider, token, label, last4, currency,
              owner_employee_id, active, created_by, created_at, updated_at)
            VALUES (:id, :t, :kind, :provider, :token, :label, :last4, :currency, :owner, :active, :by, :created, :updated)
            ON CONFLICT ON CONSTRAINT payment_instrument_token DO NOTHING
            """)
        .param("id", i.instrumentId())
        .param("t", i.tenant().value())
        .param("kind", i.kind().name())
        .param("provider", i.provider())
        .param("token", i.token())
        .param("label", i.label())
        .param("last4", i.last4())
        .param("currency", i.currency())
        .param("owner", i.ownerEmployeeId())
        .param("active", i.active())
        .param("by", i.createdBy())
        .param("created", ts(i.createdAt()))
        .param("updated", ts(i.updatedAt()))
        .update();
  }

  public Optional<Instrument> instrument(TenantId tenant, String instrumentId) {
    return jdbc.sql("SELECT * FROM payment_instrument WHERE tenant_id = :t AND instrument_id = :id")
        .param("t", tenant.value())
        .param("id", instrumentId)
        .query(FinanceRepository::instrument)
        .optional();
  }

  public Optional<Instrument> instrumentByToken(TenantId tenant, String token) {
    return jdbc.sql("SELECT * FROM payment_instrument WHERE tenant_id = :t AND token = :token")
        .param("t", tenant.value())
        .param("token", token)
        .query(FinanceRepository::instrument)
        .optional();
  }

  public List<Instrument> instruments(TenantId tenant) {
    return jdbc.sql("SELECT * FROM payment_instrument WHERE tenant_id = :t ORDER BY created_at")
        .param("t", tenant.value())
        .query(FinanceRepository::instrument)
        .list();
  }

  public void deactivateInstrument(TenantId tenant, String instrumentId, Instant now) {
    jdbc.sql(
            "UPDATE payment_instrument SET active = FALSE, updated_at = :now WHERE tenant_id = :t AND instrument_id = :id")
        .param("now", ts(now))
        .param("t", tenant.value())
        .param("id", instrumentId)
        .update();
  }

  // ------------------------------------------------------------------ payments

  public int insertPayment(Payment p) {
    return jdbc.sql(
            """
            INSERT INTO payment (payment_id, tenant_id, order_id, trip_id, instrument_id, provider, provider_ref, status,
              currency, authorized_minor, captured_minor, refunded_minor, failure_code, failure_message,
              fx_settlement_currency, fx_settlement_minor, fx_rate, fx_source, fx_quoted_at, created_at, updated_at)
            VALUES (:id, :t, :order, :trip, :instrument, :provider, :ref, :status, :currency, :auth, :cap, :ref_minor,
              :fcode, :fmsg, :fxc, :fxm, :fxr, :fxs, :fxq, :created, :updated)
            ON CONFLICT ON CONSTRAINT payment_per_order DO NOTHING
            """)
        .param("id", p.paymentId())
        .param("t", p.tenant().value())
        .param("order", p.orderId())
        .param("trip", p.tripId())
        .param("instrument", p.instrumentId())
        .param("provider", p.provider())
        .param("ref", p.providerRef())
        .param("status", p.status().name())
        .param("currency", p.currency())
        .param("auth", p.authorizedMinor())
        .param("cap", p.capturedMinor())
        .param("ref_minor", p.refundedMinor())
        .param("fcode", p.failureCode())
        .param("fmsg", p.failureMessage())
        .param("fxc", p.fx() == null ? null : p.fx().settlementCurrency())
        .param("fxm", p.fx() == null ? null : p.fx().settlementMinor())
        .param("fxr", p.fx() == null ? null : p.fx().rate())
        .param("fxs", p.fx() == null ? null : p.fx().source())
        .param("fxq", p.fx() == null ? null : ts(p.fx().quotedAt()))
        .param("created", ts(p.createdAt()))
        .param("updated", ts(p.updatedAt()))
        .update();
  }

  public void updatePayment(Payment p) {
    jdbc.sql(
            """
            UPDATE payment SET provider_ref = :ref, status = :status, captured_minor = :cap, refunded_minor = :refunded,
              failure_code = :fcode, failure_message = :fmsg, fx_settlement_currency = :fxc, fx_settlement_minor = :fxm,
              fx_rate = :fxr, fx_source = :fxs, fx_quoted_at = :fxq, updated_at = :updated
            WHERE payment_id = :id
            """)
        .param("ref", p.providerRef())
        .param("status", p.status().name())
        .param("cap", p.capturedMinor())
        .param("refunded", p.refundedMinor())
        .param("fcode", p.failureCode())
        .param("fmsg", p.failureMessage())
        .param("fxc", p.fx() == null ? null : p.fx().settlementCurrency())
        .param("fxm", p.fx() == null ? null : p.fx().settlementMinor())
        .param("fxr", p.fx() == null ? null : p.fx().rate())
        .param("fxs", p.fx() == null ? null : p.fx().source())
        .param("fxq", p.fx() == null ? null : ts(p.fx().quotedAt()))
        .param("updated", ts(p.updatedAt()))
        .param("id", p.paymentId())
        .update();
  }

  public Optional<Payment> paymentOfOrder(TenantId tenant, String orderId) {
    return jdbc.sql("SELECT * FROM payment WHERE tenant_id = :t AND order_id = :o")
        .param("t", tenant.value())
        .param("o", orderId)
        .query(FinanceRepository::payment)
        .optional();
  }

  public List<Payment> payments(TenantId tenant, Instant from, Instant to) {
    return jdbc.sql(
            "SELECT * FROM payment WHERE tenant_id = :t AND created_at >= :from AND created_at < :to ORDER BY created_at")
        .param("t", tenant.value())
        .param("from", ts(from))
        .param("to", ts(to))
        .query(FinanceRepository::payment)
        .list();
  }

  public int insertEvent(PaymentEvent e) {
    return jdbc.sql(
            """
            INSERT INTO payment_event (event_id, tenant_id, payment_id, kind, idempotency_key, currency, amount_minor, provider_ref,
              outcome, detail, item_id, occurred_at)
            VALUES (:id, :t, :p, :kind, :key, :currency, :minor, :ref, :outcome, :detail, :item, :at)
            ON CONFLICT ON CONSTRAINT payment_event_idempotency DO NOTHING
            """)
        .param("id", e.eventId())
        .param("t", e.tenant().value())
        .param("p", e.paymentId())
        .param("kind", e.kind().name())
        .param("key", e.idempotencyKey())
        .param("currency", e.amount().currency())
        .param("minor", e.amount().amountMinor())
        .param("ref", e.providerRef())
        .param("outcome", e.succeeded() ? "SUCCEEDED" : "FAILED")
        .param("detail", e.detail())
        .param("item", e.itemId())
        .param("at", ts(e.occurredAt()))
        .update();
  }

  public Optional<PaymentEvent> eventByKey(TenantId tenant, String key) {
    return jdbc.sql("SELECT * FROM payment_event WHERE tenant_id = :t AND idempotency_key = :k")
        .param("t", tenant.value())
        .param("k", key)
        .query(FinanceRepository::event)
        .optional();
  }

  public List<PaymentEvent> events(TenantId tenant, String paymentId) {
    return jdbc.sql(
            "SELECT * FROM payment_event WHERE tenant_id = :t AND payment_id = :p ORDER BY occurred_at, event_id")
        .param("t", tenant.value())
        .param("p", paymentId)
        .query(FinanceRepository::event)
        .list();
  }

  public List<PaymentEvent> events(TenantId tenant, Instant from, Instant to) {
    return jdbc.sql(
            "SELECT * FROM payment_event WHERE tenant_id = :t AND occurred_at >= :from AND occurred_at < :to ORDER BY occurred_at")
        .param("t", tenant.value())
        .param("from", ts(from))
        .param("to", ts(to))
        .query(FinanceRepository::event)
        .list();
  }

  // ------------------------------------------------------------------ payables

  public int insertPayable(Payable p) {
    return jdbc.sql(
            """
            INSERT INTO supplier_payable (payable_id, tenant_id, order_id, item_id, provider, external_ref, currency, amount_minor,
              method, status, created_at, updated_at)
            VALUES (:id, :t, :order, :item, :provider, :ref, :currency, :minor, :method, :status, :created, :updated)
            ON CONFLICT ON CONSTRAINT supplier_payable_item DO NOTHING
            """)
        .param("id", p.payableId())
        .param("t", p.tenant().value())
        .param("order", p.orderId())
        .param("item", p.itemId())
        .param("provider", p.provider())
        .param("ref", p.externalRef())
        .param("currency", p.amount().currency())
        .param("minor", p.amount().amountMinor())
        .param("method", p.method().name())
        .param("status", p.status().name())
        .param("created", ts(p.createdAt()))
        .param("updated", ts(p.updatedAt()))
        .update();
  }

  public Optional<Payable> payable(TenantId tenant, String payableId) {
    return jdbc.sql("SELECT * FROM supplier_payable WHERE tenant_id = :t AND payable_id = :id")
        .param("t", tenant.value())
        .param("id", payableId)
        .query(FinanceRepository::payable)
        .optional();
  }

  public List<Payable> payables(
      TenantId tenant, @Nullable String provider, @Nullable PayableStatus status, int limit) {
    String where =
        (provider == null ? "" : " AND provider = :provider")
            + (status == null ? "" : " AND status = :status");
    var spec =
        jdbc.sql(
                "SELECT * FROM supplier_payable WHERE tenant_id = :t"
                    + where
                    + " ORDER BY created_at DESC LIMIT :n")
            .param("t", tenant.value())
            .param("n", limit);
    if (provider != null) {
      spec = spec.param("provider", provider);
    }
    if (status != null) {
      spec = spec.param("status", status.name());
    }
    return spec.query(FinanceRepository::payable).list();
  }

  public List<Payable> payablesOfOrder(TenantId tenant, String orderId) {
    return jdbc.sql(
            "SELECT * FROM supplier_payable WHERE tenant_id = :t AND order_id = :o ORDER BY created_at")
        .param("t", tenant.value())
        .param("o", orderId)
        .query(FinanceRepository::payable)
        .list();
  }

  public boolean settlePayable(
      TenantId tenant,
      String payableId,
      PayableStatus status,
      @Nullable String invoiceReference,
      String by,
      Instant now) {
    return jdbc.sql(
                """
                UPDATE supplier_payable SET status = :status, invoice_reference = COALESCE(:inv, invoice_reference),
                  settled_by = :by, settled_at = :now, updated_at = :now
                WHERE tenant_id = :t AND payable_id = :id AND status IN ('DUE', 'INVOICED')
                """)
            .param("status", status.name())
            .param("inv", invoiceReference)
            .param("by", by)
            .param("now", ts(now))
            .param("t", tenant.value())
            .param("id", payableId)
            .update()
        == 1;
  }

  // ------------------------------------------------------------------ credits

  public int insertCredit(Credit c) {
    return jdbc.sql(
            """
            INSERT INTO travel_credit (credit_id, tenant_id, traveler_id, provider, reference, order_id, item_id, currency, amount_minor,
              status, expires_at, note, created_at, updated_at)
            VALUES (:id, :t, :traveler, :provider, :ref, :order, :item, :currency, :minor, :status, :expires, :note, :created, :updated)
            ON CONFLICT ON CONSTRAINT travel_credit_reference DO NOTHING
            """)
        .param("id", c.creditId())
        .param("t", c.tenant().value())
        .param("traveler", c.travelerId())
        .param("provider", c.provider())
        .param("ref", c.reference())
        .param("order", c.orderId())
        .param("item", c.itemId())
        .param("currency", c.amount().currency())
        .param("minor", c.amount().amountMinor())
        .param("status", c.status().name())
        .param("expires", c.expiresAt() == null ? null : ts(c.expiresAt()))
        .param("note", c.note())
        .param("created", ts(c.createdAt()))
        .param("updated", ts(c.updatedAt()))
        .update();
  }

  public Optional<Credit> credit(TenantId tenant, String creditId) {
    return jdbc.sql("SELECT * FROM travel_credit WHERE tenant_id = :t AND credit_id = :id")
        .param("t", tenant.value())
        .param("id", creditId)
        .query(FinanceRepository::credit)
        .optional();
  }

  public List<Credit> credits(
      TenantId tenant, @Nullable String travelerId, @Nullable CreditStatus status, int limit) {
    String where =
        (travelerId == null ? "" : " AND traveler_id = :traveler")
            + (status == null ? "" : " AND status = :status");
    var spec =
        jdbc.sql(
                "SELECT * FROM travel_credit WHERE tenant_id = :t"
                    + where
                    + " ORDER BY created_at DESC LIMIT :n")
            .param("t", tenant.value())
            .param("n", limit);
    if (travelerId != null) {
      spec = spec.param("traveler", travelerId);
    }
    if (status != null) {
      spec = spec.param("status", status.name());
    }
    return spec.query(FinanceRepository::credit).list();
  }

  public List<Credit> creditsOfOrder(TenantId tenant, String orderId) {
    return jdbc.sql(
            "SELECT * FROM travel_credit WHERE tenant_id = :t AND order_id = :o ORDER BY created_at")
        .param("t", tenant.value())
        .param("o", orderId)
        .query(FinanceRepository::credit)
        .list();
  }

  public boolean applyCredit(
      TenantId tenant,
      String creditId,
      String orderId,
      String by,
      @Nullable String note,
      Instant now) {
    return jdbc.sql(
                """
                UPDATE travel_credit SET status = 'APPLIED', applied_to_order_id = :order, applied_by = :by, applied_at = :now,
                  note = COALESCE(:note, note), updated_at = :now
                WHERE tenant_id = :t AND credit_id = :id AND status = 'AVAILABLE'
                """)
            .param("order", orderId)
            .param("by", by)
            .param("now", ts(now))
            .param("note", note)
            .param("t", tenant.value())
            .param("id", creditId)
            .update()
        == 1;
  }

  /** Credits past their expiry become EXPIRED; returns them so the events can be emitted. */
  public List<Credit> expireCredits(Instant now) {
    List<Credit> expiring =
        jdbc.sql(
                "SELECT * FROM travel_credit WHERE status = 'AVAILABLE' AND expires_at IS NOT NULL AND expires_at < :now")
            .param("now", ts(now))
            .query(FinanceRepository::credit)
            .list();
    for (Credit c : expiring) {
      jdbc.sql(
              "UPDATE travel_credit SET status = 'EXPIRED', updated_at = :now WHERE credit_id = :id AND status = 'AVAILABLE'")
          .param("now", ts(now))
          .param("id", c.creditId())
          .update();
    }
    return expiring;
  }

  public void recordItemCredit(String itemId, Money credit, String reference, Instant now) {
    jdbc.sql(
            "UPDATE order_item SET credit_currency = :c, credit_minor = :m, credit_reference = :r, updated_at = :now WHERE item_id = :id")
        .param("c", credit.currency())
        .param("m", credit.amountMinor())
        .param("r", reference)
        .param("now", ts(now))
        .param("id", itemId)
        .update();
  }

  // ------------------------------------------------------------------ mapping

  private static Instrument instrument(ResultSet rs, int i) throws SQLException {
    return new Instrument(
        rs.getString("instrument_id"),
        TenantId.of(rs.getString("tenant_id")),
        InstrumentKind.valueOf(rs.getString("kind")),
        rs.getString("provider"),
        rs.getString("token"),
        rs.getString("label"),
        rs.getString("last4"),
        rs.getString("currency"),
        rs.getString("owner_employee_id"),
        rs.getBoolean("active"),
        rs.getString("created_by"),
        instant(rs, "created_at"),
        instant(rs, "updated_at"));
  }

  private static Payment payment(ResultSet rs, int i) throws SQLException {
    Fx fx =
        rs.getString("fx_settlement_currency") == null
            ? null
            : new Fx(
                rs.getString("fx_settlement_currency"),
                rs.getLong("fx_settlement_minor"),
                rs.getBigDecimal("fx_rate"),
                rs.getString("fx_source"),
                instant(rs, "fx_quoted_at"));
    return new Payment(
        rs.getString("payment_id"),
        TenantId.of(rs.getString("tenant_id")),
        rs.getString("order_id"),
        rs.getString("trip_id"),
        rs.getString("instrument_id"),
        rs.getString("provider"),
        rs.getString("provider_ref"),
        PaymentStatus.valueOf(rs.getString("status")),
        rs.getString("currency"),
        rs.getLong("authorized_minor"),
        rs.getLong("captured_minor"),
        rs.getLong("refunded_minor"),
        rs.getString("failure_code"),
        rs.getString("failure_message"),
        fx,
        instant(rs, "created_at"),
        instant(rs, "updated_at"));
  }

  private static PaymentEvent event(ResultSet rs, int i) throws SQLException {
    return new PaymentEvent(
        rs.getString("event_id"),
        TenantId.of(rs.getString("tenant_id")),
        rs.getString("payment_id"),
        EventKind.valueOf(rs.getString("kind")),
        rs.getString("idempotency_key"),
        Money.of(rs.getString("currency"), rs.getLong("amount_minor")),
        rs.getString("provider_ref"),
        "SUCCEEDED".equals(rs.getString("outcome")),
        rs.getString("detail"),
        rs.getString("item_id"),
        instant(rs, "occurred_at"));
  }

  private static Payable payable(ResultSet rs, int i) throws SQLException {
    return new Payable(
        rs.getString("payable_id"),
        TenantId.of(rs.getString("tenant_id")),
        rs.getString("order_id"),
        rs.getString("item_id"),
        rs.getString("provider"),
        rs.getString("external_ref"),
        Money.of(rs.getString("currency"), rs.getLong("amount_minor")),
        SettlementMethod.valueOf(rs.getString("method")),
        PayableStatus.valueOf(rs.getString("status")),
        rs.getString("invoice_reference"),
        rs.getString("settled_by"),
        nullableInstant(rs, "settled_at"),
        instant(rs, "created_at"),
        instant(rs, "updated_at"));
  }

  private static Credit credit(ResultSet rs, int i) throws SQLException {
    return new Credit(
        rs.getString("credit_id"),
        TenantId.of(rs.getString("tenant_id")),
        rs.getString("traveler_id"),
        rs.getString("provider"),
        rs.getString("reference"),
        rs.getString("order_id"),
        rs.getString("item_id"),
        Money.of(rs.getString("currency"), rs.getLong("amount_minor")),
        CreditStatus.valueOf(rs.getString("status")),
        nullableInstant(rs, "expires_at"),
        rs.getString("applied_to_order_id"),
        rs.getString("applied_by"),
        nullableInstant(rs, "applied_at"),
        rs.getString("note"),
        instant(rs, "created_at"),
        instant(rs, "updated_at"));
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    return rs.getObject(column, OffsetDateTime.class).toInstant();
  }

  private static @Nullable Instant nullableInstant(ResultSet rs, String column)
      throws SQLException {
    OffsetDateTime v = rs.getObject(column, OffsetDateTime.class);
    return v == null ? null : v.toInstant();
  }

  private static Timestamp ts(Instant i) {
    return Timestamp.from(i);
  }
}
