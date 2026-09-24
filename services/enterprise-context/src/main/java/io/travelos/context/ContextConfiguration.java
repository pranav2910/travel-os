package io.travelos.context;

import io.travelos.common.crypto.FieldCipher;
import io.travelos.common.time.Clocks;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class ContextConfiguration {
  @Bean
  Clock clock() {
    return Clocks.micros();
  }

  /**
   * The profile field key comes from the secrets mechanism; the service refuses to start without
   * it.
   */
  @Bean
  FieldCipher fieldCipher(ProfileProperties profiles) {
    if (profiles.fieldKey() == null || profiles.fieldKey().isBlank()) {
      throw new IllegalStateException(
          "travelos.profiles.field-key (TRAVELOS_FIELD_KEY) is not set: profiles cannot be stored");
    }
    return FieldCipher.fromBase64Key(profiles.fieldKey());
  }
}
