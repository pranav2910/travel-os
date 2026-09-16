package io.travelos.context;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** travelos.context.* */
@ConfigurationProperties(prefix = "travelos.context")
public record ContextProperties(
    Duration syncInterval, Duration schedulerTick, Connectors connectors) {
  public ContextProperties {
    syncInterval = syncInterval == null ? Duration.ofSeconds(60) : syncInterval;
    schedulerTick = schedulerTick == null ? Duration.ofSeconds(10) : schedulerTick;
    connectors = connectors == null ? new Connectors(null, null) : connectors;
  }

  public record Connectors(String sandboxWebhookSecret, Integer sandboxPageSize) {
    public Connectors {
      sandboxPageSize = sandboxPageSize == null ? 3 : sandboxPageSize;
    }
  }
}
