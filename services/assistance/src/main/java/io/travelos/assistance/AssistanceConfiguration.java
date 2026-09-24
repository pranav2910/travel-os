package io.travelos.assistance;

import io.travelos.common.time.Clocks;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class AssistanceConfiguration {
  @Bean
  Clock clock() {
    return Clocks.micros();
  }
}
