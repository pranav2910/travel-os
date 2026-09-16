package io.travelos.learning.service;

import io.travelos.common.ids.IdPrefix;
import io.travelos.common.ids.Ids;
import io.travelos.common.tenant.TenantId;
import io.travelos.learning.LearningProperties;
import io.travelos.learning.events.LearningEvents;
import io.travelos.learning.metrics.LearningMetrics;
import io.travelos.learning.model.Decision;
import io.travelos.learning.model.EvidenceClass;
import io.travelos.learning.model.Outcome;
import io.travelos.learning.model.Profile;
import io.travelos.learning.model.ProfileStatus;
import io.travelos.learning.profile.ProfileBuilder;
import io.travelos.learning.profile.ProfileEvaluator;
import io.travelos.learning.store.DecisionRepository;
import io.travelos.learning.store.OutcomeRepository;
import io.travelos.learning.store.ProfileRepository;
import io.travelos.spring.outbox.Outbox;
import io.travelos.spring.web.error.ApiException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The profile lifecycle: requested (BUILDING) -> BUILT -> ELIGIBLE | REJECTED, or FAILED. Each step
 * is one transaction keyed by the profile id, so the build workflow can run a step twice and get
 * the same answer. Nothing here touches the tenant's active profile: a failed or rejected build
 * leaves whatever is active exactly as it was.
 */
@Service
public class ProfileService {
  private static final Logger log = LoggerFactory.getLogger(ProfileService.class);

  private final ProfileRepository profiles;
  private final OutcomeRepository outcomes;
  private final DecisionRepository decisions;
  private final Outbox outbox;
  private final LearningMetrics metrics;
  private final LearningProperties properties;
  private final ProfileBuilder builder;
  private final ProfileEvaluator evaluator;
  private final Clock clock;

  public ProfileService(
      ProfileRepository profiles,
      OutcomeRepository outcomes,
      DecisionRepository decisions,
      Outbox outbox,
      LearningMetrics metrics,
      LearningProperties properties,
      Clock clock) {
    this.profiles = profiles;
    this.outcomes = outcomes;
    this.decisions = decisions;
    this.outbox = outbox;
    this.metrics = metrics;
    this.properties = properties;
    this.builder = new ProfileBuilder(properties);
    this.evaluator = new ProfileEvaluator(properties);
    this.clock = clock;
  }

  public Optional<Profile> find(TenantId tenant, String profileId) {
    return profiles.find(tenant, profileId);
  }

  public List<Profile> list(TenantId tenant) {
    return profiles.list(tenant);
  }

  public Map<String, Long> countByStatus(TenantId tenant) {
    return profiles.countByStatus(tenant);
  }

  /**
   * A person asks for a build: the profile exists at once (BUILDING); the workflow does the rest.
   */
  @Transactional
  public Profile request(
      TenantId tenant,
      String requestedBy,
      @Nullable Instant cutoff,
      @Nullable EvidenceClass evidenceClass,
      @Nullable Duration window) {
    Instant now = clock.instant();
    Instant at = cutoff == null ? now : cutoff;
    if (at.isAfter(now)) {
      throw new ApiException.Unprocessable("CUTOFF_IN_FUTURE", "cutoff must not be in the future");
    }
    Duration span = window == null ? properties.window() : window;
    if (span.isNegative() || span.isZero()) {
      throw new ApiException.Unprocessable("WINDOW_INVALID", "window must be positive");
    }
    if (span.compareTo(properties.window()) > 0) {
      throw new ApiException.Unprocessable(
          "WINDOW_TOO_LONG", "window must not exceed the configured " + properties.window());
    }
    Profile p =
        new Profile(
            Ids.newId(IdPrefix.PROFILE),
            tenant,
            ProfileStatus.BUILDING,
            properties.algorithmVersion(),
            evidenceClass == null ? properties.deploymentClass() : evidenceClass,
            at,
            at.minus(span),
            builder.parameters(span),
            null,
            0,
            0,
            0,
            null,
            null,
            null,
            null,
            null,
            requestedBy,
            now,
            null,
            null,
            null);
    profiles.insert(p);
    outbox.append(LearningEvents.buildRequested(p, requestedBy, clock));
    return p;
  }

  /** The workflow's first step; idempotent, and it can also originate a build. */
  @Transactional
  public Profile begin(TenantId tenant, String profileId, @Nullable Instant cutoff, String by) {
    Optional<Profile> existing = profiles.lock(tenant, profileId);
    if (existing.isPresent()) {
      return existing.get();
    }
    Ids.require(IdPrefix.PROFILE, profileId);
    Instant now = clock.instant();
    Instant at = cutoff == null || cutoff.isAfter(now) ? now : cutoff;
    Profile p =
        new Profile(
            profileId,
            tenant,
            ProfileStatus.BUILDING,
            properties.algorithmVersion(),
            properties.deploymentClass(),
            at,
            at.minus(properties.window()),
            builder.parameters(properties.window()),
            null,
            0,
            0,
            0,
            null,
            null,
            null,
            null,
            null,
            by,
            now,
            null,
            null,
            null);
    profiles.insert(p);
    return p;
  }

  @Transactional
  public Profile compute(TenantId tenant, String profileId) {
    Profile p = locked(tenant, profileId);
    if (p.status() != ProfileStatus.BUILDING) {
      return p;
    }
    List<Outcome> dataset =
        outcomes.asOf(tenant, p.evidenceClass(), p.windowStart(), p.inputCutoff());
    ProfileBuilder.Built built = builder.build(dataset);
    Instant now = clock.instant();
    profiles.built(
        tenant,
        profileId,
        built.fingerprint(),
        built.hardOutcomes(),
        built.feedbackOutcomes(),
        built.supplierKeys(),
        built.body(),
        now);
    Profile updated = profiles.find(tenant, profileId).orElseThrow();
    outbox.append(LearningEvents.profileBuilt(updated, clock));
    metrics.build("BUILT");
    log.info(
        "profile {} built: {} hard outcomes, {} feedback, {} keys, fingerprint {}",
        profileId,
        built.hardOutcomes(),
        built.feedbackOutcomes(),
        built.supplierKeys(),
        built.fingerprint().substring(0, 12));
    return updated;
  }

  @Transactional
  public Profile evaluate(TenantId tenant, String profileId) {
    Profile p = locked(tenant, profileId);
    if (p.status() != ProfileStatus.BUILT) {
      return p;
    }
    // Only decisions inside the profile's window are replayed: what the profile could have known.
    List<Decision> history =
        decisions.asOf(tenant, p.evidenceClass(), p.windowStart(), p.inputCutoff());
    List<Outcome> evidence = outcomes.allRevisionsAsOf(tenant, p.evidenceClass(), p.inputCutoff());
    ProfileEvaluator.Report report = evaluator.evaluate(p, history, evidence);
    ProfileStatus status = report.eligible() ? ProfileStatus.ELIGIBLE : ProfileStatus.REJECTED;
    profiles.evaluated(
        tenant, profileId, status, report.verdict(), report.details(), clock.instant());
    Profile updated = profiles.find(tenant, profileId).orElseThrow();
    Map<String, Object> d = report.details();
    outbox.append(
        LearningEvents.profileEvaluated(
            updated,
            report.verdict(),
            ((Number) d.getOrDefault("holdoutDecisions", 0)).intValue(),
            ((Number) d.getOrDefault("decisionsLabeled", 0)).intValue(),
            (Double) d.get("brierBaseline"),
            (Double) d.get("brierCandidate"),
            ((Number) d.getOrDefault("hardConstraintViolations", 0)).intValue(),
            strings(d.get("reasons")),
            clock));
    metrics.evaluation(report.verdict());
    metrics.build(status.name());
    return updated;
  }

  @Transactional
  public Profile fail(TenantId tenant, String profileId, String code, @Nullable String message) {
    Profile p = locked(tenant, profileId);
    if (p.status().isFinal()) {
      return p;
    }
    profiles.failed(tenant, profileId, code, message, clock.instant());
    Profile updated = profiles.find(tenant, profileId).orElseThrow();
    outbox.append(LearningEvents.buildFailed(updated, code, message, clock));
    metrics.build("FAILED");
    return updated;
  }

  private Profile locked(TenantId tenant, String profileId) {
    return profiles
        .lock(tenant, profileId)
        .orElseThrow(
            () ->
                io.grpc.Status.NOT_FOUND
                    .withDescription("profile " + profileId)
                    .asRuntimeException());
  }

  @SuppressWarnings("unchecked")
  private static List<String> strings(@Nullable Object o) {
    return o instanceof List<?> l ? (List<String>) l : List.of();
  }
}
