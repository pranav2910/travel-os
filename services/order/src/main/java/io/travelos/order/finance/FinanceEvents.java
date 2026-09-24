package io.travelos.order.finance;

import io.travelos.common.identity.Principal;
import io.travelos.common.money.Money;
import io.travelos.events.EventEnvelope;
import io.travelos.order.finance.FinanceRecords.Credit;
import io.travelos.order.finance.FinanceRecords.Payable;
import io.travelos.order.finance.FinanceRecords.Payment;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** travel.finance.* envelopes; shapes mirror contracts/events/finance-events.schema.json. */
final class FinanceEvents {
  static final String PRODUCER = "order";

  private FinanceEvents() {}

  static EventEnvelope payment(String type, Payment p, Map<String, Object> extra, Clock clock) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("paymentId", p.paymentId());
    data.put("orderId", p.orderId());
    data.put("tripId", p.tripId());
    data.put("provider", p.provider());
    if (p.providerRef() != null) {
      data.put("providerRef", p.providerRef());
    }
    data.put("instrumentId", p.instrumentId());
    data.put("amount", money(p.authorized()));
    data.put("status", p.status().name());
    if (p.fx() != null) {
      Map<String, Object> fx = new LinkedHashMap<>();
      fx.put("settlementCurrency", p.fx().settlementCurrency());
      fx.put("settlementAmountMinor", p.fx().settlementMinor());
      fx.put("rate", p.fx().rate().doubleValue());
      fx.put("source", p.fx().source());
      fx.put("quotedAt", p.fx().quotedAt().toString());
      data.put("fx", fx);
    }
    data.putAll(extra);
    return EventEnvelope.create(type, 1, p.tenant(), p.tripId(), null, PRODUCER, data, clock);
  }

  static EventEnvelope payableRecorded(Payable p, String tripId, Clock clock) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("payableId", p.payableId());
    data.put("orderId", p.orderId());
    data.put("tripId", tripId);
    data.put("itemId", p.itemId());
    data.put("provider", p.provider());
    if (p.externalRef() != null) {
      data.put("externalRef", p.externalRef());
    }
    data.put("amount", money(p.amount()));
    data.put("method", p.method().name());
    data.put("status", p.status().name());
    return EventEnvelope.create(
        "travel.finance.payable-recorded", 1, p.tenant(), tripId, null, PRODUCER, data, clock);
  }

  static EventEnvelope payableSettled(Payable p, String tripId, Principal by, Clock clock) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("payableId", p.payableId());
    data.put("orderId", p.orderId());
    data.put("provider", p.provider());
    data.put("amount", money(p.amount()));
    data.put("status", p.status().name());
    if (p.invoiceReference() != null) {
      data.put("invoiceReference", p.invoiceReference());
    }
    data.put("settledBy", by.id());
    return EventEnvelope.create(
        "travel.finance.payable-settled", 1, p.tenant(), tripId, null, PRODUCER, data, clock);
  }

  static EventEnvelope credit(
      String type, Credit c, String correlation, @Nullable Principal by, Clock clock) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("creditId", c.creditId());
    data.put("travelerId", c.travelerId());
    if (type.endsWith("issued")) {
      if (c.orderId() != null) {
        data.put("orderId", c.orderId());
      }
      data.put("tripId", correlation);
      if (c.itemId() != null) {
        data.put("itemId", c.itemId());
      }
      data.put("reference", c.reference());
      if (c.expiresAt() != null) {
        data.put("expiresAt", c.expiresAt().toString());
      }
    }
    data.put("provider", c.provider());
    data.put("amount", money(c.amount()));
    if (type.endsWith("applied")) {
      if (c.appliedToOrderId() != null) {
        data.put("appliedToOrderId", c.appliedToOrderId());
      }
      if (c.note() != null) {
        data.put("reference", c.note());
      }
      data.put("appliedBy", by == null ? "service/order" : by.id());
    }
    return EventEnvelope.create(type, 1, c.tenant(), correlation, null, PRODUCER, data, clock);
  }

  static Map<String, Object> money(Money m) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("currency", m.currency());
    out.put("amountMinor", m.amountMinor());
    return out;
  }
}
