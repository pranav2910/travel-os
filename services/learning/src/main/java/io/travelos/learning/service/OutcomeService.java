package io.travelos.learning.service;

import io.travelos.common.ids.IdPrefix;
import io.travelos.common.ids.Ids;
import io.travelos.common.tenant.TenantId;
import io.travelos.learning.metrics.LearningMetrics;
import io.travelos.learning.model.EvidenceClass;
import io.travelos.learning.model.OrderItemRef;
import io.travelos.learning.model.Outcome;
import io.travelos.learning.model.OutcomeKind;
import io.travelos.learning.model.TripRef;
import io.travelos.learning.store.OutcomeRepository;
import io.travelos.learning.store.TripIndexRepository;
import io.travelos.spring.web.auth.RequestPrincipal;
import io.travelos.spring.web.error.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reading outcomes with the right eyes, and the one outcome only a person records: a refund. */
@Service
public class OutcomeService {
  private final OutcomeRepository outcomes;
  private final TripIndexRepository trips;
  private final LearningMetrics metrics;
  private final Clock clock;

  public OutcomeService(
      OutcomeRepository outcomes, TripIndexRepository trips, LearningMetrics metrics, Clock clock) {
    this.outcomes = outcomes;
    this.trips = trips;
    this.metrics = metrics;
    this.clock = clock;
  }

  /** The traveler of the trip, TRAVEL_ADMIN and FINANCE read; a trip we do not know is 404. */
  public List<Outcome> byTrip(RequestPrincipal me, String tripId) {
    TripRef trip = authorizedTrip(me, tripId);
    return outcomes.byTrip(me.tenant(), trip.tripId());
  }

  public TripRef authorizedTrip(RequestPrincipal me, String tripId) {
    TripRef trip =
        trips
            .find(me.tenant(), tripId)
            .orElseThrow(() -> new ApiException.NotFound("trip", tripId));
    boolean owner = trip.travelerId() != null && trip.travelerId().equals(me.employeeId());
    if (!owner && !me.hasAnyRole("TRAVEL_ADMIN", "FINANCE")) {
      throw new ApiException.NotFound("trip", tripId);
    }
    return trip;
  }

  /**
   * Finance says a refund was settled. A later statement about the same item is a new revision (a
   * correction), the same statement twice is one revision.
   */
  @Transactional
  public Outcome recordRefund(
      RequestPrincipal me,
      String tripId,
      String orderId,
      @Nullable String itemId,
      long amountMinor,
      String currency,
      String reference) {
    TenantId tenant = me.tenant();
    TripRef trip =
        trips.find(tenant, tripId).orElseThrow(() -> new ApiException.NotFound("trip", tripId));
    if (trip.orderId() != null && !trip.orderId().equals(orderId)) {
      throw new ApiException.Unprocessable(
          "ORDER_MISMATCH", "order " + orderId + " is not the trip's order");
    }
    Optional<OrderItemRef> item =
        itemId == null ? Optional.empty() : trips.item(tenant, orderId, itemId);
    if (itemId != null && item.isEmpty()) {
      throw new ApiException.Unprocessable(
          "UNKNOWN_ITEM", "item " + itemId + " is not part of order " + orderId);
    }
    String key = "refund:" + orderId + ":" + (itemId == null ? "order" : itemId);
    Optional<Outcome> current = outcomes.current(tenant, key);
    Map<String, Object> provenance = new LinkedHashMap<>();
    provenance.put("amountMinor", amountMinor);
    provenance.put("currency", currency);
    provenance.put("reference", reference);
    provenance.put("recordedBy", me.principal().id());
    if (current.isPresent()
        && Long.valueOf(amountMinor).equals(numberOf(current.get().provenance().get("amountMinor")))
        && currency.equals(current.get().provenance().get("currency"))
        && reference.equals(current.get().provenance().get("reference"))) {
      metrics.outcome(
          "REFUND_SETTLED", current.get().evidenceClass().name(), "DUPLICATE_REPRESENTATION");
      return current.get();
    }
    int revision = current.map(o -> o.revision() + 1).orElse(1);
    Instant now = clock.instant();
    String provider = item.map(OrderItemRef::provider).orElse(null);
    Outcome o =
        new Outcome(
            Ids.newId(IdPrefix.OUTCOME),
            tenant,
            key,
            revision,
            OutcomeKind.REFUND_SETTLED,
            OutcomeKind.Quality.NEUTRAL,
            item.map(OrderItemRef::supplierKey).orElse(null),
            provider,
            provider == null ? classOf(tenant, tripId) : EvidenceClass.ofProvider(provider),
            tripId,
            orderId,
            itemId,
            item.map(OrderItemRef::componentId).orElse(null),
            null,
            trip.travelerId(),
            now,
            now,
            "API",
            me.principal().id(),
            provenance);
    outcomes.insert(o);
    metrics.outcome(
        "REFUND_SETTLED", o.evidenceClass().name(), revision > 1 ? "REVISED" : "RECORDED");
    return o;
  }

  public List<Map<String, Object>> summary(TenantId tenant) {
    return outcomes.summary(tenant);
  }

  private EvidenceClass classOf(TenantId tenant, String tripId) {
    for (OrderItemRef i : trips.items(tenant, tripId)) {
      if (i.provider() != null) {
        return EvidenceClass.ofProvider(i.provider());
      }
    }
    return EvidenceClass.LIVE;
  }

  private static @Nullable Long numberOf(@Nullable Object o) {
    return o instanceof Number n ? n.longValue() : null;
  }
}
