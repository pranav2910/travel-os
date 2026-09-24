package io.travelos.context;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * travelos.profiles.*
 *
 * @param fieldKey base64 AES-256 key for the encrypted profile fields, from the secrets mechanism
 *     ({@code TRAVELOS_FIELD_KEY}); never a value in this repository
 * @param documentRetentionDays how long a revoked or expired document row is kept before the purge
 *     deletes it (the sensitive-access log outlives it)
 * @param retentionSweep how often the purge runs
 */
@ConfigurationProperties(prefix = "travelos.profiles")
public record ProfileProperties(
    String fieldKey, Integer documentRetentionDays, Duration retentionSweep) {
  public ProfileProperties {
    documentRetentionDays = documentRetentionDays == null ? 90 : documentRetentionDays;
    retentionSweep = retentionSweep == null ? Duration.ofHours(1) : retentionSweep;
  }
}
