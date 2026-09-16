package io.travelos.learning.events;

import io.travelos.common.tenant.TenantId;
import io.travelos.events.EventEnvelope;
import io.travelos.learning.model.EvidenceClass;
import io.travelos.learning.model.Feedback;
import io.travelos.learning.model.LearningMode;
import io.travelos.learning.model.Profile;
import io.travelos.learning.model.TenantConfig;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** travel.learning.* per contracts/events/learning-events.schema.json. */
public final class LearningEvents {
  public static final String PRODUCER = "learning";

  private LearningEvents() {}

  public static EventEnvelope buildRequested(Profile p, String requestedBy, Clock clock) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("profileId", p.profileId());
    data.put("requestedBy", requestedBy);
    data.put("inputCutoff", p.inputCutoff().toString());
    return envelope("travel.learning.build-requested", p.tenant(), p.profileId(), data, clock);
  }

  public static EventEnvelope profileBuilt(Profile p, Clock clock) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("profileId", p.profileId());
    data.put("algorithmVersion", p.algorithmVersion());
    data.put("evidenceClass", p.evidenceClass().name());
    data.put("inputCutoff", p.inputCutoff().toString());
    data.put("datasetFingerprint", p.datasetFingerprint() == null ? "" : p.datasetFingerprint());
    data.put("hardOutcomes", p.hardOutcomes());
    data.put("feedbackOutcomes", p.feedbackOutcomes());
    data.put("supplierKeys", p.supplierKeys());
    return envelope("travel.learning.profile-built", p.tenant(), p.profileId(), data, clock);
  }

  public static EventEnvelope profileEvaluated(
      Profile p,
      String verdict,
      int decisionsEvaluated,
      int decisionsLabeled,
      @Nullable Double brierBaseline,
      @Nullable Double brierCandidate,
      int violations,
      List<String> reasons,
      Clock clock) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("profileId", p.profileId());
    data.put("verdict", verdict);
    data.put("status", p.status().name());
    data.put("decisionsEvaluated", decisionsEvaluated);
    data.put("decisionsLabeled", decisionsLabeled);
    if (brierBaseline != null) {
      data.put("brierBaseline", brierBaseline);
    }
    if (brierCandidate != null) {
      data.put("brierCandidate", brierCandidate);
    }
    data.put("hardConstraintViolations", violations);
    data.put("reasons", reasons);
    return envelope("travel.learning.profile-evaluated", p.tenant(), p.profileId(), data, clock);
  }

  public static EventEnvelope buildFailed(
      Profile p, String code, @Nullable String message, Clock clock) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("profileId", p.profileId());
    data.put("code", code);
    if (message != null) {
      data.put("message", message.substring(0, Math.min(2000, message.length())));
    }
    return envelope("travel.learning.build-failed", p.tenant(), p.profileId(), data, clock);
  }

  public static EventEnvelope activated(
      TenantConfig c, String profileId, @Nullable String previous, String by, Clock clock) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("profileId", profileId);
    if (previous != null) {
      data.put("previousProfileId", previous);
    }
    data.put("activatedBy", by);
    data.put("mode", c.mode().name());
    data.put("configVersion", c.version());
    return envelope("travel.learning.profile-activated", c.tenant(), profileId, data, clock);
  }

  public static EventEnvelope rolledBack(
      TenantConfig c,
      @Nullable String restored,
      @Nullable String previous,
      String by,
      Clock clock) {
    Map<String, Object> data = new LinkedHashMap<>();
    if (restored != null) {
      data.put("profileId", restored);
    }
    if (previous != null) {
      data.put("previousProfileId", previous);
    }
    data.put("rolledBackBy", by);
    data.put("mode", c.mode().name());
    data.put("configVersion", c.version());
    return envelope(
        "travel.learning.profile-rolled-back",
        c.tenant(),
        restored == null ? c.tenant().value() : restored,
        data,
        clock);
  }

  public static EventEnvelope modeChanged(
      TenantConfig c, LearningMode previous, EvidenceClass deployment, String by, Clock clock) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("mode", c.mode().name());
    data.put("previousMode", previous.name());
    data.put("changedBy", by);
    data.put("deploymentClass", deployment.name());
    data.put("configVersion", c.version());
    return envelope("travel.learning.mode-changed", c.tenant(), c.tenant().value(), data, clock);
  }

  /** Structured values only: the comment is never published. */
  public static EventEnvelope feedbackRecorded(Feedback f, Clock clock) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("feedbackId", f.feedbackId());
    data.put("tripId", f.tripId());
    data.put("travelerId", f.travelerId());
    if (!f.componentId().isBlank()) {
      data.put("componentId", f.componentId());
    }
    if (f.supplierKey() != null) {
      data.put("supplierKey", f.supplierKey());
    }
    data.put("revision", f.revision());
    data.put("rating", f.rating());
    data.put("tags", f.tags());
    data.put("recordedBy", f.recordedBy());
    return envelope("travel.learning.feedback-recorded", f.tenant(), f.tripId(), data, clock);
  }

  private static EventEnvelope envelope(
      String type, TenantId tenant, String correlation, Map<String, Object> data, Clock clock) {
    return EventEnvelope.create(type, 1, tenant, correlation, null, PRODUCER, data, clock);
  }

  static Instant now(Clock clock) {
    return clock.instant();
  }
}
