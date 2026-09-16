package io.travelos.learning.service;

import io.travelos.common.tenant.TenantId;
import io.travelos.learning.LearningProperties;
import io.travelos.learning.events.LearningEvents;
import io.travelos.learning.metrics.LearningMetrics;
import io.travelos.learning.model.ActivationRecord;
import io.travelos.learning.model.LearningMode;
import io.travelos.learning.model.Profile;
import io.travelos.learning.model.ProfileStatus;
import io.travelos.learning.model.TenantConfig;
import io.travelos.learning.store.ActivationHistoryRepository;
import io.travelos.learning.store.ProfileRepository;
import io.travelos.learning.store.TenantConfigRepository;
import io.travelos.spring.outbox.Outbox;
import io.travelos.spring.web.error.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The tenant's mode and active profile. Every change takes the config row's lock and bumps its
 * version, so two activations at once are serialized and a caller that names the version it saw
 * gets a conflict instead of a silent overwrite. Every change is a history row and an event.
 */
@Service
public class ConfigService {
  private final TenantConfigRepository configs;
  private final ProfileRepository profiles;
  private final ActivationHistoryRepository history;
  private final Outbox outbox;
  private final LearningMetrics metrics;
  private final LearningProperties properties;
  private final Clock clock;

  public ConfigService(
      TenantConfigRepository configs,
      ProfileRepository profiles,
      ActivationHistoryRepository history,
      Outbox outbox,
      LearningMetrics metrics,
      LearningProperties properties,
      Clock clock) {
    this.configs = configs;
    this.profiles = profiles;
    this.history = history;
    this.outbox = outbox;
    this.metrics = metrics;
    this.properties = properties;
    this.clock = clock;
  }

  public TenantConfig config(TenantId tenant) {
    return configs
        .find(tenant)
        .orElseGet(
            () ->
                new TenantConfig(
                    tenant,
                    properties.defaultMode(),
                    null,
                    null,
                    0,
                    "service/learning",
                    Instant.EPOCH));
  }

  public List<ActivationRecord> history(TenantId tenant) {
    return history.list(tenant);
  }

  /** Why a profile may not be activated here, or empty when it may. */
  public Optional<String> activationRefusal(Profile p, Instant now) {
    if (p.status() != ProfileStatus.ELIGIBLE) {
      return Optional.of("NOT_ELIGIBLE");
    }
    if (p.evidenceClass() != properties.deploymentClass()) {
      return Optional.of("CLASS_MISMATCH");
    }
    if (!properties.algorithmVersion().equals(p.algorithmVersion())) {
      return Optional.of("INCOMPATIBLE_ALGORITHM");
    }
    if (p.inputCutoff().isBefore(now.minus(properties.staleAfter()))) {
      return Optional.of("STALE_PROFILE");
    }
    return Optional.empty();
  }

  @Transactional
  public TenantConfig activate(
      TenantId tenant, String profileId, @Nullable Long expectedVersion, String by) {
    Instant now = clock.instant();
    TenantConfig c = configs.lock(tenant, properties.defaultMode(), now);
    conflict(c, expectedVersion, "ACTIVATED");
    Profile p =
        profiles
            .find(tenant, profileId)
            .orElseThrow(() -> new ApiException.NotFound("profile", profileId));
    Optional<String> refusal = activationRefusal(p, now);
    if (refusal.isPresent()) {
      metrics.activation("ACTIVATED", "REFUSED");
      throw new ApiException.Unprocessable(
          refusal.get(), "profile " + profileId + " cannot be activated: " + refusal.get());
    }
    if (profileId.equals(c.activeProfileId())) {
      metrics.activation("ACTIVATED", "UNCHANGED");
      return c;
    }
    TenantConfig updated =
        configs.update(tenant, c.mode(), profileId, c.activeProfileId(), by, now);
    history.append(
        tenant,
        "ACTIVATED",
        profileId,
        c.activeProfileId(),
        c.mode(),
        null,
        updated.version(),
        by,
        now);
    outbox.append(LearningEvents.activated(updated, profileId, c.activeProfileId(), by, clock));
    metrics.activation("ACTIVATED", "OK");
    return updated;
  }

  /**
   * Back to the previous eligible profile when there is one, otherwise to the baseline; with {@code
   * toBaseline} straight to the baseline (no profile) whatever was active before.
   */
  @Transactional
  public TenantConfig rollback(
      TenantId tenant, @Nullable Long expectedVersion, String by, boolean toBaseline) {
    Instant now = clock.instant();
    TenantConfig c = configs.lock(tenant, properties.defaultMode(), now);
    conflict(c, expectedVersion, "ROLLED_BACK");
    if (c.activeProfileId() == null) {
      metrics.activation("ROLLED_BACK", "UNCHANGED");
      return c;
    }
    String restored = null;
    if (!toBaseline && c.previousProfileId() != null) {
      restored =
          profiles
              .find(tenant, c.previousProfileId())
              .filter(p -> activationRefusal(p, now).isEmpty())
              .map(Profile::profileId)
              .orElse(null);
    }
    TenantConfig updated = configs.update(tenant, c.mode(), restored, c.activeProfileId(), by, now);
    history.append(
        tenant,
        "ROLLED_BACK",
        restored,
        c.activeProfileId(),
        c.mode(),
        null,
        updated.version(),
        by,
        now);
    outbox.append(LearningEvents.rolledBack(updated, restored, c.activeProfileId(), by, clock));
    metrics.activation("ROLLED_BACK", "OK");
    return updated;
  }

  @Transactional
  public TenantConfig setMode(
      TenantId tenant, LearningMode mode, @Nullable Long expectedVersion, String by) {
    Instant now = clock.instant();
    TenantConfig c = configs.lock(tenant, properties.defaultMode(), now);
    conflict(c, expectedVersion, "MODE_CHANGED");
    if (c.mode() == mode) {
      metrics.activation("MODE_CHANGED", "UNCHANGED");
      return c;
    }
    TenantConfig updated =
        configs.update(tenant, mode, c.activeProfileId(), c.previousProfileId(), by, now);
    history.append(
        tenant,
        "MODE_CHANGED",
        c.activeProfileId(),
        null,
        mode,
        c.mode(),
        updated.version(),
        by,
        now);
    outbox.append(
        LearningEvents.modeChanged(updated, c.mode(), properties.deploymentClass(), by, clock));
    metrics.activation("MODE_CHANGED", "OK");
    return updated;
  }

  private void conflict(TenantConfig c, @Nullable Long expectedVersion, String action) {
    if (expectedVersion != null && expectedVersion != c.version()) {
      metrics.activation(action, "CONFLICT");
      throw new ApiException.Conflict(
          "VERSION_CONFLICT", "config is at version " + c.version() + ", not " + expectedVersion);
    }
  }
}
