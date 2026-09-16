package io.travelos.learning.api;

import io.travelos.learning.LearningProperties;
import io.travelos.learning.model.ActivationRecord;
import io.travelos.learning.model.EvidenceClass;
import io.travelos.learning.model.Feedback;
import io.travelos.learning.model.LearningMode;
import io.travelos.learning.model.Outcome;
import io.travelos.learning.model.Profile;
import io.travelos.learning.model.TenantConfig;
import io.travelos.learning.service.ConfigService;
import io.travelos.learning.service.FeedbackService;
import io.travelos.learning.service.OutcomeService;
import io.travelos.learning.service.ProfileService;
import io.travelos.learning.service.ResolveService;
import io.travelos.spring.web.auth.RequestPrincipal;
import io.travelos.spring.web.error.ApiException;
import io.travelos.spring.web.idempotency.IdempotencyKeyHeader;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Inspection, evaluation, activation, mode and rollback for travel admins (Finance reads); outcomes
 * and feedback for travelers on their own trips; refunds for Finance. Everything tenant-scoped:
 * another tenant's profile or trip is 404, never 403.
 */
@RestController
@RequestMapping(path = "/api/v1/learning", produces = "application/json")
public class LearningController {
  private final ConfigService configs;
  private final ProfileService profiles;
  private final OutcomeService outcomes;
  private final FeedbackService feedback;
  private final LearningProperties properties;

  public LearningController(
      ConfigService configs,
      ProfileService profiles,
      OutcomeService outcomes,
      FeedbackService feedback,
      LearningProperties properties) {
    this.configs = configs;
    this.profiles = profiles;
    this.outcomes = outcomes;
    this.feedback = feedback;
    this.properties = properties;
  }

  // ------------------------------------------------------------------ views

  public record ConfigView(
      String tenantId,
      String mode,
      String deploymentClass,
      @Nullable String activeProfileId,
      @Nullable String previousProfileId,
      long version,
      String updatedBy,
      Instant updatedAt) {
    static ConfigView from(TenantConfig c, EvidenceClass deployment) {
      return new ConfigView(
          c.tenant().value(),
          c.mode().name(),
          deployment.name(),
          c.activeProfileId(),
          c.previousProfileId(),
          c.version(),
          c.updatedBy(),
          c.updatedAt());
    }
  }

  public record ProfileView(
      String profileId,
      String tenantId,
      String status,
      String algorithmVersion,
      String evidenceClass,
      boolean synthetic,
      Instant inputCutoff,
      Instant windowStart,
      Map<String, Object> parameters,
      @Nullable String datasetFingerprint,
      int hardOutcomes,
      int feedbackOutcomes,
      int supplierKeys,
      @Nullable Map<String, Object> suppliers,
      int travelersWithPreferences,
      @Nullable Map<String, Object> evaluation,
      @Nullable String verdict,
      @Nullable String failureCode,
      @Nullable String failureMessage,
      String requestedBy,
      Instant createdAt,
      @Nullable Instant builtAt,
      @Nullable Instant evaluatedAt) {
    @SuppressWarnings("unchecked")
    static ProfileView from(Profile p) {
      Map<String, Object> body = p.body() == null ? Map.of() : p.body();
      Map<String, Object> suppliers =
          body.get("suppliers") instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
      int travelers = body.get("travelers") instanceof Map<?, ?> m ? m.size() : 0;
      return new ProfileView(
          p.profileId(),
          p.tenant().value(),
          p.status().name(),
          p.algorithmVersion(),
          p.evidenceClass().name(),
          p.evidenceClass() == EvidenceClass.SANDBOX,
          p.inputCutoff(),
          p.windowStart(),
          p.parameters(),
          p.datasetFingerprint(),
          p.hardOutcomes(),
          p.feedbackOutcomes(),
          p.supplierKeys(),
          suppliers,
          travelers,
          p.evaluation(),
          p.verdict(),
          p.failureCode(),
          p.failureMessage(),
          p.requestedBy(),
          p.createdAt(),
          p.builtAt(),
          p.evaluatedAt());
    }
  }

  public record OutcomeView(
      String outcomeId,
      String logicalKey,
      int revision,
      String kind,
      String quality,
      @Nullable String supplierKey,
      @Nullable String provider,
      String evidenceClass,
      @Nullable String tripId,
      @Nullable String orderId,
      @Nullable String itemId,
      @Nullable String componentId,
      @Nullable String disruptionId,
      Instant observedAt,
      Instant recordedAt,
      String source,
      String sourceRef,
      Map<String, Object> provenance) {
    static OutcomeView from(Outcome o) {
      return new OutcomeView(
          o.outcomeId(),
          o.logicalKey(),
          o.revision(),
          o.kind().name(),
          o.quality().name(),
          o.supplierKey(),
          o.provider(),
          o.evidenceClass().name(),
          o.tripId(),
          o.orderId(),
          o.itemId(),
          o.componentId(),
          o.disruptionId(),
          o.observedAt(),
          o.recordedAt(),
          o.source(),
          o.sourceRef(),
          o.provenance());
    }
  }

  /** The comment is shown to the traveler and to admins as what it is: untrusted text. */
  public record FeedbackView(
      String feedbackId,
      String tripId,
      String travelerId,
      String componentId,
      @Nullable String supplierKey,
      int revision,
      int rating,
      List<String> tags,
      @Nullable String comment,
      String recordedBy,
      Instant recordedAt) {
    static FeedbackView from(Feedback f) {
      return new FeedbackView(
          f.feedbackId(),
          f.tripId(),
          f.travelerId(),
          f.componentId(),
          f.supplierKey(),
          f.revision(),
          f.rating(),
          f.tags(),
          f.comment(),
          f.recordedBy(),
          f.recordedAt());
    }
  }

  /** {@code window} is an ISO-8601 duration (PT2H, P30D) no longer than the configured maximum. */
  public record BuildRequest(
      @Nullable Instant cutoff, @Nullable String evidenceClass, @Nullable String window) {}

  public record ModeRequest(@NotNull String mode, @Nullable Long expectedVersion) {}

  /** {@code toBaseline} on a rollback: no profile at all, instead of the previous eligible one. */
  public record ActivationRequest(@Nullable Long expectedVersion, @Nullable Boolean toBaseline) {}

  public record FeedbackRequest(
      @NotBlank String tripId,
      @Nullable String componentId,
      @Min(1) @Max(5) int rating,
      @Nullable List<@NotBlank String> tags,
      @Nullable @Size(max = 2000) String comment) {}

  public record RefundRequest(
      @NotBlank String tripId,
      @NotBlank String orderId,
      @Nullable String itemId,
      @Min(0) long amountMinor,
      @NotBlank @Size(min = 3, max = 3) String currency,
      @NotBlank @Size(max = 200) String reference) {}

  // ------------------------------------------------------------------ config, mode, activation

  @GetMapping("/config")
  public ConfigView config(@AuthenticationPrincipal RequestPrincipal me) {
    reader(me);
    return ConfigView.from(configs.config(me.tenant()), properties.deploymentClass());
  }

  @PutMapping(path = "/config", consumes = "application/json")
  public ConfigView setMode(
      @AuthenticationPrincipal RequestPrincipal me,
      @IdempotencyKeyHeader String idempotencyKey,
      @Valid @RequestBody ModeRequest request) {
    admin(me);
    LearningMode mode;
    try {
      mode = LearningMode.valueOf(request.mode().trim().toUpperCase());
    } catch (IllegalArgumentException e) {
      throw new ApiException.Unprocessable("UNKNOWN_MODE", "mode must be OFF, SHADOW or ACTIVE");
    }
    return ConfigView.from(
        configs.setMode(me.tenant(), mode, request.expectedVersion(), me.principal().id()),
        properties.deploymentClass());
  }

  @GetMapping("/history")
  public List<ActivationRecord> history(@AuthenticationPrincipal RequestPrincipal me) {
    reader(me);
    return configs.history(me.tenant());
  }

  @PostMapping(path = "/profiles/{profileId}/activation", consumes = "application/json")
  public ConfigView activate(
      @AuthenticationPrincipal RequestPrincipal me,
      @IdempotencyKeyHeader String idempotencyKey,
      @PathVariable String profileId,
      @RequestBody(required = false) @Nullable ActivationRequest request) {
    admin(me);
    return ConfigView.from(
        configs.activate(
            me.tenant(),
            profileId,
            request == null ? null : request.expectedVersion(),
            me.principal().id()),
        properties.deploymentClass());
  }

  @PostMapping(path = "/rollback", consumes = "application/json")
  public ConfigView rollback(
      @AuthenticationPrincipal RequestPrincipal me,
      @IdempotencyKeyHeader String idempotencyKey,
      @RequestBody(required = false) @Nullable ActivationRequest request) {
    admin(me);
    return ConfigView.from(
        configs.rollback(
            me.tenant(),
            request == null ? null : request.expectedVersion(),
            me.principal().id(),
            request != null && Boolean.TRUE.equals(request.toBaseline())),
        properties.deploymentClass());
  }

  // ------------------------------------------------------------------ profiles

  @PostMapping(path = "/profiles", consumes = "application/json")
  public ResponseEntity<ProfileView> build(
      @AuthenticationPrincipal RequestPrincipal me,
      @IdempotencyKeyHeader String idempotencyKey,
      @RequestBody(required = false) @Nullable BuildRequest request) {
    admin(me);
    EvidenceClass cls = null;
    if (request != null && request.evidenceClass() != null && !request.evidenceClass().isBlank()) {
      try {
        cls = EvidenceClass.valueOf(request.evidenceClass().trim().toUpperCase());
      } catch (IllegalArgumentException e) {
        throw new ApiException.Unprocessable(
            "UNKNOWN_CLASS", "evidenceClass must be SANDBOX or LIVE");
      }
    }
    java.time.Duration window = null;
    if (request != null && request.window() != null && !request.window().isBlank()) {
      try {
        window = java.time.Duration.parse(request.window().trim());
      } catch (java.time.format.DateTimeParseException e) {
        throw new ApiException.Unprocessable(
            "WINDOW_INVALID", "window must be an ISO-8601 duration");
      }
    }
    Profile p =
        profiles.request(
            me.tenant(),
            me.principal().id(),
            request == null ? null : request.cutoff(),
            cls,
            window);
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(ProfileView.from(p));
  }

  @GetMapping("/profiles")
  public List<ProfileView> list(@AuthenticationPrincipal RequestPrincipal me) {
    reader(me);
    return profiles.list(me.tenant()).stream().map(ProfileView::from).toList();
  }

  @GetMapping("/profiles/{profileId}")
  public ProfileView profile(
      @AuthenticationPrincipal RequestPrincipal me, @PathVariable String profileId) {
    reader(me);
    return profiles
        .find(me.tenant(), profileId)
        .map(ProfileView::from)
        .orElseThrow(() -> new ApiException.NotFound("profile", profileId));
  }

  /** What the active profile says about me: my own learned preferences and nothing else. */
  @GetMapping("/preferences")
  public Map<String, Object> preferences(@AuthenticationPrincipal RequestPrincipal me) {
    TenantConfig c = configs.config(me.tenant());
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("mode", c.mode().name());
    out.put("activeProfileId", c.activeProfileId());
    Map<String, Map<String, Object>> mine =
        c.activeProfileId() == null || me.employeeId() == null
            ? Map.of()
            : profiles
                .find(me.tenant(), c.activeProfileId())
                .map(p -> ResolveService.travelerKeys(p, me.employeeId()))
                .orElse(Map.of());
    out.put("preferences", mine);
    return out;
  }

  // ------------------------------------------------------------------ outcomes, feedback, refunds

  @GetMapping("/outcomes")
  public List<OutcomeView> outcomes(
      @AuthenticationPrincipal RequestPrincipal me, @RequestParam String tripId) {
    return outcomes.byTrip(me, tripId).stream().map(OutcomeView::from).toList();
  }

  @PostMapping(path = "/outcomes/refunds", consumes = "application/json")
  public OutcomeView refund(
      @AuthenticationPrincipal RequestPrincipal me,
      @IdempotencyKeyHeader String idempotencyKey,
      @Valid @RequestBody RefundRequest request) {
    if (!me.hasRole("FINANCE")) {
      throw new ApiException.Forbidden("NOT_FINANCE", "only Finance records a settled refund");
    }
    return OutcomeView.from(
        outcomes.recordRefund(
            me,
            request.tripId(),
            request.orderId(),
            request.itemId(),
            request.amountMinor(),
            request.currency().toUpperCase(),
            request.reference()));
  }

  @PostMapping(path = "/feedback", consumes = "application/json")
  public ResponseEntity<FeedbackView> feedback(
      @AuthenticationPrincipal RequestPrincipal me,
      @IdempotencyKeyHeader String idempotencyKey,
      @Valid @RequestBody FeedbackRequest request) {
    FeedbackService.Recorded r =
        feedback.record(
            me,
            request.tripId(),
            request.componentId(),
            request.rating(),
            request.tags() == null ? List.of() : request.tags(),
            request.comment());
    return ResponseEntity.status(
            r.result() == FeedbackService.Result.UNCHANGED ? HttpStatus.OK : HttpStatus.CREATED)
        .body(FeedbackView.from(r.feedback()));
  }

  @GetMapping("/feedback")
  public List<FeedbackView> feedbackOf(
      @AuthenticationPrincipal RequestPrincipal me, @RequestParam String tripId) {
    outcomes.authorizedTrip(me, tripId);
    return feedback.byTrip(me.tenant(), tripId).stream().map(FeedbackView::from).toList();
  }

  /** Bounded, tenant-scoped counts: enough to see the evidence, nothing about any person. */
  @GetMapping("/summary")
  public Map<String, Object> summary(@AuthenticationPrincipal RequestPrincipal me) {
    reader(me);
    TenantConfig c = configs.config(me.tenant());
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("tenantId", me.tenant().value());
    out.put("mode", c.mode().name());
    out.put("deploymentClass", properties.deploymentClass().name());
    out.put("activeProfileId", c.activeProfileId());
    out.put("previousProfileId", c.previousProfileId());
    out.put("configVersion", c.version());
    out.put("outcomes", outcomes.summary(me.tenant()));
    out.put("profiles", profiles.countByStatus(me.tenant()));
    out.put("algorithmVersion", properties.algorithmVersion());
    out.put("maxAdjustment", properties.maxAdjustment());
    out.put("minSamples", properties.minSamples());
    out.put("windowDays", properties.window().toDays());
    return out;
  }

  private static void admin(RequestPrincipal me) {
    if (!me.hasRole("TRAVEL_ADMIN")) {
      throw new ApiException.Forbidden("NOT_A_TRAVEL_ADMIN", "TRAVEL_ADMIN role required");
    }
  }

  private static void reader(RequestPrincipal me) {
    if (!me.hasAnyRole("TRAVEL_ADMIN", "FINANCE")) {
      throw new ApiException.Forbidden(
          "NOT_A_LEARNING_READER", "TRAVEL_ADMIN or FINANCE role required");
    }
  }
}
