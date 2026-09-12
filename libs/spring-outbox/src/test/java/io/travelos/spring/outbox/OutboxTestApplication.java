package io.travelos.spring.outbox;

import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
class OutboxTestApplication {

  /**
   * Boot wires every SpanExporter bean into its tracer provider; this one keeps spans in memory.
   */
  @Bean
  InMemorySpanExporter inMemorySpanExporter() {
    return InMemorySpanExporter.create();
  }
}
