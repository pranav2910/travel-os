package io.travelos.spring.outbox;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "travelos.outbox")
public record OutboxProperties(
    /** Relay poll interval. Latency floor for event delivery. */
    Duration pollInterval,
    /** Rows relayed per poll, per instance. */
    Integer batchSize,
    /** Per-record wait for the broker's ack. */
    Duration sendTimeout) {

  public OutboxProperties {
    pollInterval = pollInterval == null ? Duration.ofMillis(500) : pollInterval;
    batchSize = batchSize == null ? 100 : batchSize;
    sendTimeout = sendTimeout == null ? Duration.ofSeconds(10) : sendTimeout;
  }
}
