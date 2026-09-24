package io.travelos.order.finance;

import io.travelos.common.money.Money;
import io.travelos.order.finance.FinanceRecords.Fx;
import io.travelos.order.finance.FinanceRecords.Instrument;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stripe as a LIVE payment provider (Phase 5, ADR-0017): PaymentIntents with manual capture, paid
 * off-session with a saved payment method (the instrument's token is a {@code pm_...} id), captured
 * for what the suppliers charged, cancelled when nothing was booked, refunded per item. Stripe
 * honours the {@code Idempotency-Key} header on every POST, so the ledger's keys are passed
 * through. The secret key never appears in logs or errors; a {@code sk_test_} key moves no money.
 */
public class StripePaymentProvider implements PaymentProvider {
  public static final String PROVIDER = "stripe";
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final RestClient http;
  private final FinanceProperties.Stripe props;

  public StripePaymentProvider(RestClient.Builder builder, FinanceProperties.Stripe props) {
    this.props = props;
    this.http =
        builder
            .baseUrl(props.baseUrl())
            .defaultHeader("Authorization", "Bearer " + props.secretKey())
            .defaultHeader("Stripe-Version", "2024-06-20")
            .build();
  }

  @Override
  public String provider() {
    return PROVIDER;
  }

  @Override
  public boolean live() {
    return !props.testMode();
  }

  @Override
  public Authorization authorize(String key, Instrument instrument, Money amount, String orderId) {
    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("amount", String.valueOf(amount.amountMinor()));
    form.add("currency", amount.currency().toLowerCase());
    form.add("payment_method", instrument.token());
    form.add("confirm", "true");
    form.add("off_session", "true");
    form.add("capture_method", "manual");
    form.add("metadata[order_id]", orderId);
    form.add("metadata[tenant_id]", instrument.tenant().value());
    form.add("expand[]", "latest_charge.balance_transaction");
    JsonNode intent;
    try {
      intent = post("/v1/payment_intents", key, form);
    } catch (PaymentException e) {
      if ("card_declined".equals(e.code())
          || "CARD_DECLINED".equalsIgnoreCase(e.code())
          || e.code().startsWith("DECLINED:")) {
        return Authorization.declined("CARD_DECLINED", e.getMessage());
      }
      throw e;
    }
    String status = intent.path("status").asString("");
    if (!"requires_capture".equals(status) && !"succeeded".equals(status)) {
      return Authorization.declined(
          "AUTHORIZATION_" + status.toUpperCase(), "stripe left the intent " + status);
    }
    return Authorization.approved(intent.path("id").asString(), fx(intent, amount));
  }

  @Override
  public Capture capture(String key, String providerRef, Money amount) {
    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("amount_to_capture", String.valueOf(amount.amountMinor()));
    form.add("expand[]", "latest_charge.balance_transaction");
    JsonNode intent = post("/v1/payment_intents/" + providerRef + "/capture", key, form);
    return new Capture(intent.path("id").asString(), amount, fx(intent, amount));
  }

  @Override
  public void voidAuthorization(String key, String providerRef) {
    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("cancellation_reason", "abandoned");
    post("/v1/payment_intents/" + providerRef + "/cancel", key, form);
  }

  @Override
  public Refund refund(String key, String providerRef, Money amount, String reason) {
    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("payment_intent", providerRef);
    form.add("amount", String.valueOf(amount.amountMinor()));
    form.add("metadata[reason]", reason.length() > 400 ? reason.substring(0, 400) : reason);
    JsonNode refund = post("/v1/refunds", key, form);
    return new Refund(refund.path("id").asString(), amount);
  }

  @Override
  public List<Transaction> transactions(String tenantId, Instant from, Instant to) {
    List<Transaction> out = new ArrayList<>();
    String startingAfter = null;
    for (int page = 0; page < 20; page++) {
      String path =
          "/v1/balance_transactions?limit=100&created[gte]="
              + from.getEpochSecond()
              + "&created[lt]="
              + to.getEpochSecond()
              + (startingAfter == null ? "" : "&starting_after=" + startingAfter);
      JsonNode list = get(path);
      for (JsonNode t : list.path("data")) {
        out.add(
            new Transaction(
                t.path("source").asString(t.path("id").asString()),
                t.path("type").asString("").toUpperCase(),
                Money.of(
                    t.path("currency").asString("usd").toUpperCase(), t.path("amount").asLong()),
                null,
                Instant.ofEpochSecond(t.path("created").asLong())));
        startingAfter = t.path("id").asString();
      }
      if (!list.path("has_more").asBoolean(false)) {
        break;
      }
    }
    return out;
  }

  private static @Nullable Fx fx(JsonNode intent, Money amount) {
    JsonNode bt = intent.path("latest_charge").path("balance_transaction");
    if (!bt.isObject() || !bt.hasNonNull("exchange_rate")) {
      return null;
    }
    return new Fx(
        bt.path("currency").asString("").toUpperCase(),
        bt.path("amount").asLong(),
        new BigDecimal(bt.path("exchange_rate").asString()),
        "stripe balance_transaction " + bt.path("id").asString(),
        Instant.ofEpochSecond(bt.path("created").asLong()));
  }

  private JsonNode post(String path, String key, MultiValueMap<String, String> form) {
    try {
      String body =
          http.post()
              .uri(path)
              .header("Idempotency-Key", key)
              .contentType(MediaType.APPLICATION_FORM_URLENCODED)
              .body(form)
              .retrieve()
              .body(String.class);
      return JSON.readTree(body);
    } catch (RuntimeException e) {
      throw failure(e);
    }
  }

  private JsonNode get(String path) {
    try {
      return JSON.readTree(http.get().uri(path).retrieve().body(String.class));
    } catch (RuntimeException e) {
      throw failure(e);
    }
  }

  static PaymentException failure(RuntimeException e) {
    if (e instanceof RestClientResponseException r) {
      int status = r.getStatusCode().value();
      String code = "HTTP_" + status;
      String message = "stripe answered " + status;
      try {
        JsonNode error = JSON.readTree(r.getResponseBodyAsString()).path("error");
        if (error.hasNonNull("code")) {
          code = error.get("code").asString();
        } else if (error.hasNonNull("type")) {
          code = error.get("type").asString();
        }
        if (error.hasNonNull("decline_code")) {
          code = "DECLINED:" + error.get("decline_code").asString();
        }
        if (error.hasNonNull("message")) {
          message = "stripe: " + error.get("message").asString();
        }
      } catch (RuntimeException ignored) {
        // not JSON
      }
      if (status == 402) {
        return new PaymentException(
            code.startsWith("DECLINED:") ? code : "card_declined", message, false);
      }
      return new PaymentException(code, message, status >= 500 || status == 429);
    }
    if (e instanceof ResourceAccessException) {
      return new PaymentException("TRANSPORT", "stripe: " + e.getMessage(), true);
    }
    if (e instanceof PaymentException p) {
      return p;
    }
    return new PaymentException("PROVIDER_ERROR", "stripe: " + e.getMessage(), true);
  }

  static Map<String, String> flat(MultiValueMap<String, String> form) {
    Map<String, String> m = new LinkedHashMap<>();
    form.forEach((k, v) -> m.put(k, String.join(",", v)));
    return m;
  }
}
