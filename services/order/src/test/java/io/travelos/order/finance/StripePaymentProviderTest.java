package io.travelos.order.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import io.travelos.common.money.Money;
import io.travelos.common.tenant.TenantId;
import io.travelos.order.finance.FinanceRecords.Instrument;
import io.travelos.order.finance.FinanceRecords.InstrumentKind;
import java.time.Instant;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** Contract test against Stripe's documented PaymentIntent / Refund shapes on a scripted server. */
class StripePaymentProviderTest {
  private MockRestServiceServer server;
  private StripePaymentProvider stripe;
  private final Instrument card =
      new Instrument(
          "pmi_01ARZ3NDEKTSV4RRFFQ69G5FAV",
          TenantId.of("acme"),
          InstrumentKind.CORPORATE_CARD,
          "stripe",
          "pm_1Nabc",
          "Corporate Visa",
          "4242",
          "USD",
          null,
          true,
          "human/carol",
          Instant.EPOCH,
          Instant.EPOCH);

  @BeforeEach
  void setUp() {
    RestClient.Builder builder = RestClient.builder();
    server = MockRestServiceServer.bindTo(builder).build();
    stripe =
        new StripePaymentProvider(
            builder, new FinanceProperties.Stripe("sk_test_abc", "https://api.stripe.test", null));
  }

  @Test
  void testKeysAreNotLive() {
    assertThat(stripe.live()).isFalse();
    assertThat(
            new StripePaymentProvider(
                    RestClient.builder(), new FinanceProperties.Stripe("sk_live_x", null, null))
                .live())
        .isTrue();
  }

  @Test
  void authorizeCreatesAManualCaptureIntentWithTheIdempotencyKey() {
    server
        .expect(requestTo("https://api.stripe.test/v1/payment_intents"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("Authorization", "Bearer sk_test_abc"))
        .andExpect(header("Idempotency-Key", "ord_1:AUTHORIZE:1"))
        .andExpect(content().contentType(MediaType.APPLICATION_FORM_URLENCODED))
        .andExpect(content().string(Matchers.containsString("amount=47500")))
        .andExpect(content().string(Matchers.containsString("currency=usd")))
        .andExpect(content().string(Matchers.containsString("capture_method=manual")))
        .andExpect(content().string(Matchers.containsString("payment_method=pm_1Nabc")))
        .andExpect(content().string(Matchers.containsString("off_session=true")))
        .andRespond(
            withSuccess(
                "{\"id\":\"pi_1\",\"status\":\"requires_capture\",\"amount\":47500,\"currency\":\"usd\"}",
                MediaType.APPLICATION_JSON));
    PaymentProvider.Authorization a =
        stripe.authorize("ord_1:AUTHORIZE:1", card, Money.usd(47500), "ord_1");
    server.verify();
    assertThat(a.outcome()).isEqualTo(PaymentProvider.Outcome.APPROVED);
    assertThat(a.providerRef()).isEqualTo("pi_1");
    assertThat(a.fx()).isNull();
  }

  @Test
  void aDeclineIsAnAnswerNotAnError() {
    server
        .expect(requestTo("https://api.stripe.test/v1/payment_intents"))
        .andRespond(
            withStatus(HttpStatus.PAYMENT_REQUIRED)
                .contentType(MediaType.APPLICATION_JSON)
                .body(
                    "{\"error\":{\"type\":\"card_error\",\"code\":\"card_declined\",\"decline_code\":\"insufficient_funds\",\"message\":\"Your card has insufficient funds.\"}}"));
    PaymentProvider.Authorization a = stripe.authorize("k", card, Money.usd(100), "ord_1");
    assertThat(a.outcome()).isEqualTo(PaymentProvider.Outcome.DECLINED);
    assertThat(a.reasonCode()).isEqualTo("CARD_DECLINED");
    assertThat(a.message()).contains("insufficient funds");
  }

  @Test
  void captureRecordsTheProvidersConversionAsProvenance() {
    server
        .expect(requestTo("https://api.stripe.test/v1/payment_intents/pi_1/capture"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("Idempotency-Key", "ord_1:CAPTURE:1"))
        .andExpect(content().string(Matchers.containsString("amount_to_capture=47500")))
        .andRespond(
            withSuccess(
                "{\"id\":\"pi_1\",\"status\":\"succeeded\",\"amount\":47500,\"currency\":\"usd\",\"latest_charge\":{\"id\":\"ch_1\",\"balance_transaction\":{\"id\":\"txn_1\",\"amount\":43700,\"currency\":\"eur\",\"exchange_rate\":0.92,\"created\":1790000000}}}",
                MediaType.APPLICATION_JSON));
    PaymentProvider.Capture c = stripe.capture("ord_1:CAPTURE:1", "pi_1", Money.usd(47500));
    server.verify();
    assertThat(c.captured()).isEqualTo(Money.usd(47500));
    assertThat(c.fx()).isNotNull();
    assertThat(c.fx().settlementCurrency()).isEqualTo("EUR");
    assertThat(c.fx().settlementMinor()).isEqualTo(43700);
    assertThat(c.fx().rate()).isEqualByComparingTo("0.92");
    assertThat(c.fx().source()).contains("txn_1");
  }

  @Test
  void voidAndRefundUseTheirOwnKeys() {
    server
        .expect(requestTo("https://api.stripe.test/v1/payment_intents/pi_1/cancel"))
        .andExpect(header("Idempotency-Key", "ord_1:VOID:1"))
        .andRespond(
            withSuccess("{\"id\":\"pi_1\",\"status\":\"canceled\"}", MediaType.APPLICATION_JSON));
    server
        .expect(requestTo("https://api.stripe.test/v1/refunds"))
        .andExpect(header("Idempotency-Key", "ord_1:REFUND:itm_1:1"))
        .andExpect(content().string(Matchers.containsString("payment_intent=pi_1")))
        .andExpect(content().string(Matchers.containsString("amount=40000")))
        .andRespond(
            withSuccess(
                "{\"id\":\"re_1\",\"status\":\"succeeded\",\"amount\":40000}",
                MediaType.APPLICATION_JSON));
    stripe.voidAuthorization("ord_1:VOID:1", "pi_1");
    PaymentProvider.Refund r =
        stripe.refund("ord_1:REFUND:itm_1:1", "pi_1", Money.usd(40000), "hotel released");
    server.verify();
    assertThat(r.providerRef()).isEqualTo("re_1");
  }

  @Test
  void balanceTransactionsAreTheProvidersSideOfReconciliation() {
    server
        .expect(requestTo(Matchers.containsString("/v1/balance_transactions?limit=100&created")))
        .andRespond(
            withSuccess(
                "{\"data\":[{\"id\":\"txn_1\",\"type\":\"charge\",\"amount\":47500,\"currency\":\"usd\",\"source\":\"ch_1\",\"created\":1790000000}],\"has_more\":false}",
                MediaType.APPLICATION_JSON));
    var txns =
        stripe.transactions(
            "acme", Instant.ofEpochSecond(1789990000), Instant.ofEpochSecond(1790010000));
    assertThat(txns)
        .singleElement()
        .satisfies(
            t -> {
              assertThat(t.kind()).isEqualTo("CHARGE");
              assertThat(t.amount()).isEqualTo(Money.usd(47500));
            });
  }

  @Test
  void serverErrorsAreRetryableAndRefusalsAreNot() {
    server
        .expect(requestTo("https://api.stripe.test/v1/payment_intents/pi_x/cancel"))
        .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
    assertThatThrownBy(() -> stripe.voidAuthorization("k1", "pi_x"))
        .isInstanceOf(PaymentProvider.PaymentException.class)
        .satisfies(e -> assertThat(((PaymentProvider.PaymentException) e).retryable()).isTrue());
    server.reset();
    server
        .expect(requestTo("https://api.stripe.test/v1/payment_intents/pi_y/cancel"))
        .andRespond(
            withStatus(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body(
                    "{\"error\":{\"type\":\"invalid_request_error\",\"code\":\"resource_missing\",\"message\":\"No such payment_intent\"}}"));
    assertThatThrownBy(() -> stripe.voidAuthorization("k2", "pi_y"))
        .isInstanceOf(PaymentProvider.PaymentException.class)
        .satisfies(
            e -> {
              assertThat(((PaymentProvider.PaymentException) e).code())
                  .isEqualTo("resource_missing");
              assertThat(((PaymentProvider.PaymentException) e).retryable()).isFalse();
            });
  }
}
