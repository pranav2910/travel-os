package io.travelos.assistance;

import io.travelos.assistance.model.Priority;
import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** travelos.assistance.*: service levels per priority and the escalation policy. */
@ConfigurationProperties(prefix = "travelos.assistance")
public record AssistanceProperties(
    @Nullable Sla sla,
    @Nullable Duration escalationSweep,
    @Nullable Integer maxEscalationLevel,
    @Nullable Notifications notifications,
    @Nullable Safety safety) {

  public AssistanceProperties {
    sla = sla == null ? new Sla(null, null, null, null) : sla;
    escalationSweep = escalationSweep == null ? Duration.ofMinutes(1) : escalationSweep;
    maxEscalationLevel = maxEscalationLevel == null ? 3 : maxEscalationLevel;
    notifications =
        notifications == null
            ? new Notifications(null, null, null, null, null, null)
            : notifications;
    safety = safety == null ? new Safety(null) : safety;
  }

  /**
   * Phase 8: delivery channels. Email goes through SendGrid when an API key exists, chat through a
   * Slack incoming webhook when one exists; in-app always. Credentials come from the secrets
   * mechanism and are never logged.
   */
  public record Notifications(
      @Nullable String sendgridApiKey,
      @Nullable String sendgridBaseUrl,
      @Nullable String fromAddress,
      @Nullable String slackWebhookUrl,
      @Nullable Duration dispatchInterval,
      @Nullable Integer maxAttempts) {
    public Notifications {
      sendgridBaseUrl =
          sendgridBaseUrl == null || sendgridBaseUrl.isBlank()
              ? "https://api.sendgrid.com"
              : sendgridBaseUrl;
      fromAddress =
          fromAddress == null || fromAddress.isBlank() ? "travel@travelos.invalid" : fromAddress;
      dispatchInterval = dispatchInterval == null ? Duration.ofSeconds(5) : dispatchInterval;
      maxAttempts = maxAttempts == null ? 5 : maxAttempts;
    }
  }

  /**
   * @param checkinGrace how long an affected traveler has to check in on a HIGH or CRITICAL
   *     advisory before a SAFETY case is opened for them
   */
  public record Safety(@Nullable Duration checkinGrace) {
    public Safety {
      checkinGrace = checkinGrace == null ? Duration.ofHours(4) : checkinGrace;
    }
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
