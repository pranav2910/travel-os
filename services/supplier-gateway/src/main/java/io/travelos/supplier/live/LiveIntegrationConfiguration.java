package io.travelos.supplier.live;

import io.travelos.supplier.live.duffel.DuffelAirSupplier;
import io.travelos.supplier.live.duffel.DuffelClient;
import io.travelos.supplier.live.hotelbeds.HotelbedsClient;
import io.travelos.supplier.live.hotelbeds.HotelbedsHotelSupplier;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Real adapters exist only when their credentials do. Without them the platform runs on the
 * SIMULATED sandboxes and says so in every capability answer.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LiveIntegrationProperties.class)
class LiveIntegrationConfiguration {
  private static final Logger log = LoggerFactory.getLogger(LiveIntegrationConfiguration.class);

  @Bean
  @ConditionalOnExpression("'${travelos.integrations.duffel.access-token:}' != ''")
  DuffelAirSupplier duffelAirSupplier(
      LiveIntegrationProperties properties, RestClient.Builder builder, Clock clock) {
    LiveIntegrationProperties.Duffel d = properties.duffel();
    log.info(
        "duffel adapter registered ({} token, {})",
        d.testEnvironment() ? "TEST" : "LIVE",
        d.baseUrl());
    return new DuffelAirSupplier(new DuffelClient(builder, d), d, clock);
  }

  @Bean
  @ConditionalOnExpression(
      "'${travelos.integrations.hotelbeds.api-key:}' != '' && '${travelos.integrations.hotelbeds.secret:}' != ''")
  HotelbedsHotelSupplier hotelbedsHotelSupplier(
      LiveIntegrationProperties properties, RestClient.Builder builder, Clock clock) {
    LiveIntegrationProperties.Hotelbeds h = properties.hotelbeds();
    log.info(
        "hotelbeds adapter registered ({} endpoint {})",
        h.testEnvironment() ? "TEST" : "LIVE",
        h.baseUrl());
    return new HotelbedsHotelSupplier(new HotelbedsClient(builder, h, clock), h, clock);
  }
}
