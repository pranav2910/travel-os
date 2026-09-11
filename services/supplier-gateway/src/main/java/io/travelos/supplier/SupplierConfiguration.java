package io.travelos.supplier;

import java.time.Clock;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SupplierProperties.class)
class SupplierConfiguration {

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  SupplierRegistry supplierRegistry(List<AirSupplier> suppliers, SupplierProperties properties) {
    return new SupplierRegistry(suppliers, properties);
  }
}
