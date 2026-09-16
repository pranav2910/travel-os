package io.travelos.learning.service;

import io.travelos.common.tenant.TenantId;
import io.travelos.contracts.optimization.v1.LearningAdjustment;
import io.travelos.contracts.optimization.v1.LearningInputs;
import io.travelos.learning.LearningProperties;
import io.travelos.learning.metrics.LearningMetrics;
import io.travelos.learning.model.LearningMode;
import io.travelos.learning.model.Profile;
import io.travelos.learning.model.TenantConfig;
import io.travelos.learning.store.ProfileRepository;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * What the planner pins to an attempt: the tenant's mode, the active profile's bounded adjustments
 * for this traveler, or a named fallback (baseline). Read-only. The workflow calls this exactly
 * once per attempt through an activity and never again during replay.
 */
@Service
public class ResolveService {
  private final ConfigService configs;
  private final ProfileRepository profiles;
  private final LearningMetrics metrics;
  private final LearningProperties properties;
  private final Clock clock;

  public ResolveService(
      ConfigService configs,
      ProfileRepository profiles,
      LearningMetrics metrics,
      LearningProperties properties,
      Clock clock) {
    this.configs = configs;
    this.profiles = profiles;
    this.metrics = metrics;
    this.properties = properties;
    this.clock = clock;
  }

  public LearningInputs resolve(TenantId tenant, @Nullable String travelerId) {
    TenantConfig c = configs.config(tenant);
    LearningInputs.Builder b = LearningInputs.newBuilder().setMode(c.mode().name());
    if (c.mode() == LearningMode.OFF) {
      metrics.resolution("OFF", "MODE_OFF");
      return b.setFallbackReason("MODE_OFF").build();
    }
    if (c.activeProfileId() == null) {
      metrics.resolution(c.mode().name(), "NO_ACTIVE_PROFILE");
      return b.setFallbackReason("NO_ACTIVE_PROFILE").build();
    }
    Optional<Profile> active = profiles.find(tenant, c.activeProfileId());
    if (active.isEmpty()) {
      metrics.resolution(c.mode().name(), "NO_ACTIVE_PROFILE");
      return b.setFallbackReason("NO_ACTIVE_PROFILE").build();
    }
    Profile p = active.get();
    Optional<String> refusal = configs.activationRefusal(p, clock.instant());
    if (refusal.isPresent()) {
      String reason = "NOT_ELIGIBLE".equals(refusal.get()) ? "NO_ACTIVE_PROFILE" : refusal.get();
      metrics.resolution(c.mode().name(), reason);
      return b.setProfileId(p.profileId()).setFallbackReason(reason).build();
    }
    b.setProfileId(p.profileId())
        .setAlgorithmVersion(p.algorithmVersion())
        .setEvidenceClass(p.evidenceClass().name())
        .setMaxAdjustment(maxAdjustment(p));
    for (Map.Entry<String, Map<String, Object>> e : suppliers(p).entrySet()) {
      double adj = number(e.getValue().get("adjustment"));
      if (adj == 0.0) {
        continue;
      }
      b.addAdjustments(
          LearningAdjustment.newBuilder()
              .setSupplierKey(e.getKey())
              .setAdjustment(adj)
              .setEstimate(number(e.getValue().get("estimate")))
              .setSamples((int) number(e.getValue().get("samples")))
              .setSource("SUPPLIER_RELIABILITY")
              .setReason(String.valueOf(e.getValue().getOrDefault("reason", ""))));
    }
    if (travelerId != null && !travelerId.isBlank()) {
      for (Map.Entry<String, Map<String, Object>> e : travelerKeys(p, travelerId).entrySet()) {
        double adj = number(e.getValue().get("adjustment"));
        if (adj == 0.0) {
          continue;
        }
        b.addAdjustments(
            LearningAdjustment.newBuilder()
                .setSupplierKey(e.getKey())
                .setAdjustment(adj)
                .setEstimate(number(e.getValue().get("estimate")))
                .setSamples((int) number(e.getValue().get("samples")))
                .setSource("TRAVELER_PREFERENCE")
                .setReason(String.valueOf(e.getValue().getOrDefault("reason", ""))));
      }
    }
    metrics.resolution(c.mode().name(), c.mode() == LearningMode.ACTIVE ? "APPLIED" : "SHADOW");
    return b.build();
  }

  private double maxAdjustment(Profile p) {
    Object m = p.body() == null ? null : p.body().get("maxAdjustment");
    double max = m instanceof Number n ? n.doubleValue() : properties.maxAdjustment();
    return Math.min(LearningProperties.HARD_MAX_ADJUSTMENT, Math.abs(max));
  }

  @SuppressWarnings("unchecked")
  static Map<String, Map<String, Object>> suppliers(Profile p) {
    Map<String, Object> body = p.body() == null ? Map.of() : p.body();
    return body.get("suppliers") instanceof Map<?, ?> m
        ? (Map<String, Map<String, Object>>) (Map<?, ?>) m
        : Map.of();
  }

  @SuppressWarnings("unchecked")
  public static Map<String, Map<String, Object>> travelerKeys(Profile p, String travelerId) {
    Map<String, Object> body = p.body() == null ? Map.of() : p.body();
    if (!(body.get("travelers") instanceof Map<?, ?> travelers)) {
      return Map.of();
    }
    Object mine = travelers.get(travelerId);
    return mine instanceof Map<?, ?> m
        ? (Map<String, Map<String, Object>>) (Map<?, ?>) m
        : new LinkedHashMap<>();
  }

  private static double number(@Nullable Object o) {
    return o instanceof Number n ? n.doubleValue() : 0.0;
  }
}
