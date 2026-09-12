package io.travelos.audit;

import io.travelos.common.time.Clocks;
import io.travelos.events.EventCodec;
import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class AuditApplication {

  public static void main(String[] args) {
    SpringApplication.run(AuditApplication.class, args);
  }

  @Bean
  Clock clock() {
    return Clocks.micros();
  }

  @Bean
  EventCodec eventCodec() {
    return new EventCodec();
  }
}
