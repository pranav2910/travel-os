package io.travelos.context.events;

import io.travelos.common.identity.Principal;
import io.travelos.context.model.Connector;
import io.travelos.context.model.DemandCandidate;
import io.travelos.context.model.DemandStatus;
import io.travelos.context.model.SourceRef;
import io.travelos.context.model.SyncRun;
import io.travelos.events.EventEnvelope;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** travel.demand.* per contracts/events/demand-events.schema.json. */
public final class DemandEvents {
  public static final String PRODUCER = "enterprise-context";

  private DemandEvents() {}

  public static EventEnvelope syncRequested(
      Connector c,
      SyncRun run,
      @Nullable Principal by,
      @Nullable String notificationId,
      Clock clock) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("connectorId", c.connectorId());
    data.put("kind", c.kind().name());
    data.put("provider", c.provider());
    data.put("runId", run.runId());
    data.put("trigger", run.trigger().name());
    if (by != null) {
      data.put("requestedBy", by.id());
    }
    if (notificationId != null) {
      data.put("notificationId", notificationId);
    }
    return EventEnvelope.create(
        "travel.demand.sync-requested", 1, c.tenant(), run.runId(), null, PRODUCER, data, clock);
  }

  public static EventEnvelope syncCompleted(Connector c, SyncRun run, Clock clock) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("connectorId", c.connectorId());
    data.put("kind", c.kind().name());
    data.put("runId", run.runId());
    data.put("pages", run.pages());
    data.put("itemsSeen", run.itemsSeen());
    data.put("itemsChanged", run.itemsChanged());
    data.put("candidatesTouched", run.candidatesTouched());
    return EventEnvelope.create(
        "travel.demand.sync-completed", 1, c.tenant(), run.runId(), null, PRODUCER, data, clock);
  }

  public static EventEnvelope syncFailed(
      Connector c, SyncRun run, String code, @Nullable String message, Clock clock) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("connectorId", c.connectorId());
    data.put("kind", c.kind().name());
    data.put("runId", run.runId());
    data.put("code", code);
    if (message != null) {
      data.put("message", message.substring(0, Math.min(2000, message.length())));
    }
    return EventEnvelope.create(
        "travel.demand.sync-failed", 1, c.tenant(), run.runId(), null, PRODUCER, data, clock);
  }

  public static EventEnvelope detected(DemandCandidate c, Clock clock) {
    return candidate("travel.demand.candidate-detected", c, core(c), clock);
  }

  public static EventEnvelope updated(
      DemandCandidate c, DemandStatus previous, String reason, Clock clock) {
    Map<String, Object> data = core(c);
    data.put("previousStatus", previous.name());
    data.put("reason", reason);
    return candidate("travel.demand.candidate-updated", c, data, clock);
  }

  public static EventEnvelope withdrawn(
      DemandCandidate c, DemandStatus previous, String reason, String explanation, Clock clock) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("candidateId", c.candidateId());
    data.put("travelerId", c.travelerId());
    data.put("previousStatus", previous.name());
    data.put("reason", reason);
    data.put("sources", sources(c.sources()));
    data.put("explanation", explanation);
    return candidate("travel.demand.candidate-withdrawn", c, data, clock);
  }

  public static EventEnvelope dismissed(
      DemandCandidate c,
      DemandStatus previous,
      Principal by,
      @Nullable String reason,
      Clock clock) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("candidateId", c.candidateId());
    data.put("travelerId", c.travelerId());
    data.put("previousStatus", previous.name());
    data.put("dismissedBy", by.id());
    if (reason != null) {
      data.put("reason", reason);
    }
    return candidate("travel.demand.candidate-dismissed", c, data, clock);
  }

  public static EventEnvelope converted(DemandCandidate c, Principal by, Clock clock) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("candidateId", c.candidateId());
    data.put("travelerId", c.travelerId());
    data.put("tripId", c.tripId());
    data.put("convertedBy", by.id());
    data.put("sources", sources(c.sources()));
    if (c.destination() != null) {
      data.put("destination", c.destination());
    }
    if (c.startDate() != null) {
      data.put("startDate", c.startDate().toString());
      data.put("endDate", String.valueOf(c.endDate()));
    }
    return candidate("travel.demand.candidate-converted", c, data, clock);
  }

  public static EventEnvelope changedAfterConversion(
      DemandCandidate c, String change, String explanation, Clock clock) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("candidateId", c.candidateId());
    data.put("travelerId", c.travelerId());
    data.put("tripId", c.tripId());
    data.put("change", change);
    data.put("sources", sources(c.sources()));
    data.put("explanation", explanation);
    return candidate("travel.demand.candidate-changed-after-conversion", c, data, clock);
  }

  private static Map<String, Object> core(DemandCandidate c) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("candidateId", c.candidateId());
    data.put("travelerId", c.travelerId());
    data.put("status", c.status().name());
    if (c.destination() != null) {
      data.put("destination", c.destination());
    }
    if (c.origin() != null) {
      data.put("origin", c.origin());
    }
    if (c.startDate() != null) {
      data.put("startDate", c.startDate().toString());
    }
    if (c.endDate() != null) {
      data.put("endDate", c.endDate().toString());
    }
    if (c.timeZone() != null) {
      data.put("timeZone", c.timeZone());
    }
    data.put("rulesVersion", c.rulesVersion());
    data.put("sources", sources(c.sources()));
    data.put("missing", c.missing());
    data.put("reviewReasons", c.reviewReasons());
    data.put("explanation", c.explanation());
    return data;
  }

  private static List<Map<String, Object>> sources(List<SourceRef> refs) {
    return refs.stream().map(SourceRef::toMap).toList();
  }

  private static EventEnvelope candidate(
      String type, DemandCandidate c, Map<String, Object> data, Clock clock) {
    return EventEnvelope.create(type, 1, c.tenant(), c.candidateId(), null, PRODUCER, data, clock);
  }
}
