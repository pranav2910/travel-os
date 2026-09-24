package io.travelos.order.finance;

import java.time.Duration;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * travelos.finance.*
 *
 * @param settlement how each supplier is paid (CARD_AT_SUPPLIER | BALANCE | INVOICE), by provider
 *     id; unknown providers are CARD_AT_SUPPLIER
 * @param stripe live payment provider, present only when {@code secretKey} is (secrets mechanism)
 */
@ConfigurationProperties(prefix = "travelos.finance")
public record FinanceProperties(
    @Nullable Map<String, FinanceRecords.SettlementMethod> settlement,
    @Nullable Stripe stripe,
    @Nullable Duration creditSweep) {

  public FinanceProperties {
    settlement = settlement == null ? Map.of() : Map.copyOf(settlement);
    stripe = stripe == null ? new Stripe(null, null, null) : stripe;
    creditSweep = creditSweep == null ? Duration.ofHours(1) : creditSweep;
  }

  public FinanceRecords.SettlementMethod methodFor(String provider) {
    return settlement.getOrDefault(provider, FinanceRecords.SettlementMethod.CARD_AT_SUPPLIER);
  }

  public record Stripe(
      @Nullable String secretKey, @Nullable String baseUrl, @Nullable Duration timeout) {
    public Stripe {
      baseUrl = baseUrl == null || baseUrl.isBlank() ? "https://api.stripe.com" : baseUrl;
      timeout = timeout == null ? Duration.ofSeconds(20) : timeout;
    }

    public boolean configured() {
      return secretKey != null && !secretKey.isBlank();
    }

    public boolean testMode() {
      return secretKey != null && secretKey.startsWith("sk_test_");
    }
  }
}
