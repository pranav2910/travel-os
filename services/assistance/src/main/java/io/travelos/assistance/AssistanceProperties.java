package io.travelos.assistance;

import io.travelos.assistance.model.Priority;
import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** travelos.assistance.*: service levels per priority and the escalation policy. */
@ConfigurationProperties(prefix = "travelos.assistance")
public record AssistanceProperties(
    @Nullable Sla sla, @Nullable Duration escalationSweep, @Nullable Integer maxEscalationLevel) {

  public AssistanceProperties {
    sla = sla == null ? new Sla(null, null, null, null) : sla;
    escalationSweep = escalationSweep == null ? Duration.ofMinutes(1) : escalationSweep;
    maxEscalationLevel = maxEscalationLevel == null ? 3 : maxEscalationLevel;
  }

  public Duration slaFor(Priority priority) {
    return switch (priority) {
      case CRITICAL -> sla.critical();
      case HIGH -> sla.high();
      case NORMAL -> sla.normal();
      case LOW -> sla.low();
    };
  }

  public record Sla(
      @Nullable Duration critical,
      @Nullable Duration high,
      @Nullable Duration normal,
      @Nullable Duration low) {
    public Sla {
      critical = critical == null ? Duration.ofHours(1) : critical;
      high = high == null ? Duration.ofHours(4) : high;
      normal = normal == null ? Duration.ofHours(24) : normal;
      low = low == null ? Duration.ofHours(72) : low;
    }
  }
}
