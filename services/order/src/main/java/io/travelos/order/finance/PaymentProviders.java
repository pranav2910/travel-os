package io.travelos.order.finance;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The registered payment providers by id. */
public final class PaymentProviders {
  private final Map<String, PaymentProvider> byId = new LinkedHashMap<>();

  public PaymentProviders(List<PaymentProvider> providers) {
    for (PaymentProvider p : providers) {
      byId.put(p.provider(), p);
    }
  }

  public Optional<PaymentProvider> find(String provider) {
    return Optional.ofNullable(byId.get(provider));
  }

  public PaymentProvider require(String provider) {
    return find(provider)
        .orElseThrow(
            () ->
                new PaymentProvider.PaymentException(
                    "PROVIDER_UNKNOWN", "no payment provider " + provider, false));
  }

  public List<String> providers() {
    return List.copyOf(byId.keySet());
  }
}
