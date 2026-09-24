package io.travelos.order.finance;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(FinanceProperties.class)
class FinanceConfiguration {
  private static final Logger log = LoggerFactory.getLogger(FinanceConfiguration.class);

  @Bean
  @ConditionalOnExpression("'${travelos.finance.stripe.secret-key:}' != ''")
  StripePaymentProvider stripePaymentProvider(
      FinanceProperties properties, RestClient.Builder builder) {
    log.info(
        "stripe payment provider registered ({} mode)",
        properties.stripe().testMode() ? "TEST" : "LIVE");
    return new StripePaymentProvider(builder, properties.stripe());
  }

  @Bean
  PaymentProviders paymentProviders(List<PaymentProvider> providers) {
    return new PaymentProviders(providers);
  }
}
