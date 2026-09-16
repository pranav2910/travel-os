package io.travelos.learning.service;

import io.travelos.common.ids.IdPrefix;
import io.travelos.common.ids.Ids;
import io.travelos.common.tenant.TenantId;
import io.travelos.learning.events.LearningEvents;
import io.travelos.learning.metrics.LearningMetrics;
import io.travelos.learning.model.EvidenceClass;
import io.travelos.learning.model.Feedback;
import io.travelos.learning.model.OrderItemRef;
import io.travelos.learning.model.Outcome;
import io.travelos.learning.model.OutcomeKind;
import io.travelos.learning.model.TripRef;
import io.travelos.learning.store.FeedbackRepository;
import io.travelos.learning.store.OutcomeRepository;
import io.travelos.learning.store.TripIndexRepository;
import io.travelos.spring.outbox.Outbox;
import io.travelos.spring.web.auth.RequestPrincipal;
import io.travelos.spring.web.error.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Explicit traveler feedback: authorized against the actual traveler of the actual trip, structured
 * values validated against a fixed vocabulary, one revision per change (the same values twice are
 * one revision), the free text kept for a person and never used.
 */
@Service
public class FeedbackService {
  public static final Set<String> TAGS =
      Set.of(
          "ON_TIME",
          "DELAYED",
          "CLEAN",
          "NOISY",
          "FRIENDLY_STAFF",
          "POOR_SERVICE",
          "GOOD_VALUE",
          "OVERPRICED",
          "COMFORTABLE",
          "UNCOMFORTABLE",
          "WOULD_REPEAT",
          "AVOID");
  private static final Set<String> ELIGIBLE_TRIP_STATUS = Set.of("BOOKED", "COMPLETED");

  public enum Result {
    RECORDED,
    REVISED,
    UNCHANGED
  }

  public record Recorded(Feedback feedback, Result result) {}

  private final FeedbackRepository feedbacks;
  private final OutcomeRepository outcomes;
  private final TripIndexRepository trips;
  private final Outbox outbox;
  private final LearningMetrics metrics;
  private final Clock clock;

  public FeedbackService(
      FeedbackRepository feedbacks,
      OutcomeRepository outcomes,
      TripIndexRepository trips,
      Outbox outbox,
      LearningMetrics metrics,
      Clock clock) {
    this.feedbacks = feedbacks;
    this.outcomes = outcomes;
    this.trips = trips;
    this.outbox = outbox;
    this.metrics = metrics;
    this.clock = clock;
  }

  @Transactional
  public Recorded record(
      RequestPrincipal me,
      String tripId,
      @Nullable String componentId,
      int rating,
      List<String> tags,
      @Nullable String comment) {
    TenantId tenant = me.tenant();
    TripRef trip =
        trips.find(tenant, tripId).orElseThrow(() -> new ApiException.NotFound("trip", tripId));
    if (trip.travelerId() == null || !trip.travelerId().equals(me.employeeId())) {
      metrics.feedback("REFUSED");
      throw new ApiException.Forbidden(
          "NOT_THE_TRAVELER", "only the trip's traveler gives feedback");
    }
    if (!ELIGIBLE_TRIP_STATUS.contains(trip.status())) {
      metrics.feedback("REFUSED");
      throw new ApiException.Unprocessable(
          "TRIP_NOT_ELIGIBLE",
          "feedback needs a booked or completed trip, this one is " + trip.status());
    }
    if (rating < 1 || rating > 5) {
      metrics.feedback("REFUSED");
      throw new ApiException.Unprocessable("RATING_OUT_OF_RANGE", "rating must be 1..5");
    }
    Set<String> cleanTags = new TreeSet<>();
    for (String t : tags) {
      if (!TAGS.contains(t)) {
        metrics.feedback("REFUSED");
        throw new ApiException.Unprocessable(
            "UNKNOWN_TAG", "tag " + t + " is not in the vocabulary");
      }
      cleanTags.add(t);
    }
    if (comment != null && comment.length() > 2000) {
      throw new ApiException.Unprocessable(
          "COMMENT_TOO_LONG", "comment must be at most 2000 characters");
    }
    String component = componentId == null ? "" : componentId.trim();
    String supplierKey = null;
    String provider = null;
    if (!component.isBlank()) {
      Optional<OrderItemRef> item =
          trips.items(tenant, tripId).stream()
              .filter(i -> component.equals(i.componentId()) || component.equals(i.itemId()))
              .findFirst();
      if (item.isEmpty()) {
        metrics.feedback("REFUSED");
        throw new ApiException.Unprocessable(
            "UNKNOWN_COMPONENT", "component " + component + " is not part of trip " + tripId);
      }
      supplierKey = item.get().supplierKey();
      provider = item.get().provider();
    }
    Optional<Feedback> current = feedbacks.current(tenant, tripId, me.employeeId(), component);
    List<String> tagList = List.copyOf(cleanTags);
    if (current.isPresent()
        && current.get().rating() == rating
        && current.get().tags().equals(tagList)) {
      metrics.feedback("UNCHANGED");
      return new Recorded(current.get(), Result.UNCHANGED);
    }
    int revision = current.map(f -> f.revision() + 1).orElse(1);
    Instant now = clock.instant();
    Feedback f =
        new Feedback(
            Ids.newId(IdPrefix.FEEDBACK),
            tenant,
            tripId,
            me.employeeId(),
            component,
            supplierKey,
            provider,
            revision,
            rating,
            tagList,
            comment,
            me.principal().id(),
            now);
    feedbacks.insert(f);
    Map<String, Object> provenance = new LinkedHashMap<>();
    provenance.put("feedbackId", f.feedbackId());
    provenance.put("rating", rating);
    provenance.put("tags", tagList);
    provenance.put("revision", revision);
    outcomes.insert(
        new Outcome(
            Ids.newId(IdPrefix.OUTCOME),
            tenant,
            "feedback:" + tripId + ":" + me.employeeId() + ":" + component,
            revision,
            OutcomeKind.FEEDBACK,
            OutcomeKind.Quality.NEUTRAL,
            supplierKey,
            provider,
            provider == null ? classOf(tenant, tripId) : EvidenceClass.ofProvider(provider),
            tripId,
            trip.orderId(),
            null,
            component.isBlank() ? null : component,
            null,
            me.employeeId(),
            now,
            now,
            "FEEDBACK",
            f.feedbackId(),
            provenance));
    outbox.append(LearningEvents.feedbackRecorded(f, clock));
    metrics.feedback(revision == 1 ? "RECORDED" : "REVISED");
    return new Recorded(f, revision == 1 ? Result.RECORDED : Result.REVISED);
  }

  public List<Feedback> byTrip(TenantId tenant, String tripId) {
    return feedbacks.byTrip(tenant, tripId);
  }

  private EvidenceClass classOf(TenantId tenant, String tripId) {
    for (OrderItemRef i : trips.items(tenant, tripId)) {
      if (i.provider() != null) {
        return EvidenceClass.ofProvider(i.provider());
      }
    }
    return EvidenceClass.LIVE;
  }
}
