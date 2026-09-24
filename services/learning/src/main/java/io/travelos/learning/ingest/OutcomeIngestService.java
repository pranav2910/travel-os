package io.travelos.learning.ingest;

import io.travelos.common.ids.IdPrefix;
import io.travelos.common.ids.Ids;
import io.travelos.common.tenant.TenantId;
import io.travelos.events.EventEnvelope;
import io.travelos.learning.LearningProperties;
import io.travelos.learning.metrics.LearningMetrics;
import io.travelos.learning.model.Decision;
import io.travelos.learning.model.EvidenceClass;
import io.travelos.learning.model.OrderItemRef;
import io.travelos.learning.model.Outcome;
import io.travelos.learning.model.OutcomeKind;
import io.travelos.learning.store.DecisionRepository;
import io.travelos.learning.store.OutcomeRepository;
import io.travelos.learning.store.ProcessedEventRepository;
import io.travelos.learning.store.TripIndexRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns what the platform announced into outcome records: exactly once per event id (Kafka
 * redelivery), exactly once per logical outcome revision (the same fact told twice, by two events,
 * lands on one row). Nothing is inferred: an order cancellation records a cancellation, not a
 * refund; a booking confirmation records a confirmation, not a completed trip.
 */
@Service
public class OutcomeIngestService {
  private static final Logger log = LoggerFactory.getLogger(OutcomeIngestService.class);

  public enum Result {
    RECORDED,
    DUPLICATE_EVENT,
    IGNORED
  }

  private final ProcessedEventRepository processed;
  private final OutcomeRepository outcomes;
  private final DecisionRepository decisions;
  private final TripIndexRepository trips;
  private final LearningMetrics metrics;
  private final LearningProperties properties;
  private final Clock clock;

  public OutcomeIngestService(
      ProcessedEventRepository processed,
      OutcomeRepository outcomes,
      DecisionRepository decisions,
      TripIndexRepository trips,
      LearningMetrics metrics,
      LearningProperties properties,
      Clock clock) {
    this.processed = processed;
    this.outcomes = outcomes;
    this.decisions = decisions;
    this.trips = trips;
    this.metrics = metrics;
    this.properties = properties;
    this.clock = clock;
  }

  @Transactional
  public Result ingest(EventEnvelope event) {
    Instant now = clock.instant();
    if (!processed.markProcessed(event.eventId(), event.eventType(), now)) {
      metrics.outcome("EVENT", "NONE", "DUPLICATE_EVENT");
      return Result.DUPLICATE_EVENT;
    }
    TenantId tenant = TenantId.of(event.tenantId());
    Map<String, Object> d = event.data();
    Instant observed = event.occurredAt();
    boolean recorded =
        switch (event.eventType()) {
          case "travel.trip.created" -> {
            trips.upsert(
                tenant, str(d, "tripId"), str(d, "travelerId"), null, "SUBMITTED", null, null, now);
            yield false;
          }
          case "travel.trip.booked" -> {
            trips.upsert(
                tenant, str(d, "tripId"), null, str(d, "orderId"), "BOOKED", observed, null, now);
            yield false;
          }
          case "travel.trip.completed" -> tripCompleted(tenant, event, now);
          case "travel.trip.cancellation-requested" -> {
            // the reservation is being released: not cancelled yet, no longer a trip to rate
            trips.upsert(
                tenant, str(d, "tripId"), null, str(d, "orderId"), "CANCELLING", null, null, now);
            yield false;
          }
          case "travel.trip.cancelled" -> {
            trips.upsert(tenant, str(d, "tripId"), null, null, "CANCELLED", null, null, now);
            yield record(
                outcome(
                    tenant,
                    "cancel:" + str(d, "tripId"),
                    OutcomeKind.CANCELLED_BY_TRAVELER,
                    null,
                    null,
                    tripClass(tenant, str(d, "tripId")),
                    str(d, "tripId"),
                    null,
                    null,
                    null,
                    null,
                    event,
                    Map.of("cancelledBy", str(d, "cancelledBy"), "reason", str(d, "reason"))));
          }
          case "travel.trip.failed" -> {
            trips.upsert(tenant, str(d, "tripId"), null, null, "FAILED", null, null, now);
            yield false;
          }
          case "travel.order.confirmed" -> orderItems(tenant, event, now, false);
          case "travel.order.changed" -> orderItems(tenant, event, now, true);
          case "travel.order.failed" -> orderFailed(tenant, event, now);
          case "travel.order.cancelled" ->
              record(
                  outcome(
                      tenant,
                      "cancel:" + str(d, "tripId"),
                      OutcomeKind.CANCELLED_BY_TRAVELER,
                      null,
                      null,
                      tripClass(tenant, str(d, "tripId")),
                      str(d, "tripId"),
                      str(d, "orderId"),
                      null,
                      null,
                      null,
                      event,
                      provenance(
                          "cancelledBy", str(d, "cancelledBy"),
                          "refundQuoted", d.get("refund"),
                          "note", "a quoted refund is not a settled refund")));
          case "travel.order.compensation-failed" -> compensationFailed(tenant, event, now);
          // Phase 5 (ADR-0017): a refund the finance ledger settled is the authoritative refund
          // outcome; the manual Finance statement stays for refunds the platform did not process.
          case "travel.finance.payment-refunded" -> financeRefund(tenant, event);
          case "travel.order.exposure-resolved" ->
              record(
                  outcome(
                      tenant,
                      "exposure:" + str(d, "exposureId"),
                      OutcomeKind.EXPOSURE_RESOLVED,
                      null,
                      null,
                      tripClass(tenant, str(d, "tripId")),
                      str(d, "tripId"),
                      str(d, "orderId"),
                      null,
                      null,
                      null,
                      event,
                      provenance(
                          "exposureId", str(d, "exposureId"),
                          "resolvedBy", str(d, "resolvedBy"),
                          "note", "a person closed the exposure; not a refund receipt")));
          case "travel.disruption.detected" -> disruptionDetected(tenant, event, 1);
          case "travel.disruption.impact-confirmed" -> disruptionDetected(tenant, event, 2);
          case "travel.disruption.resolved" ->
              record(
                  outcome(
                      tenant,
                      "recovery:" + str(d, "disruptionId"),
                      OutcomeKind.RECOVERY_RESOLVED,
                      null,
                      null,
                      tripClass(tenant, str(d, "tripId")),
                      str(d, "tripId"),
                      str(d, "orderId"),
                      null,
                      null,
                      str(d, "disruptionId"),
                      event,
                      provenance(
                          "autonomyOutcome", str(d, "autonomyOutcome"),
                          "incrementalCost", d.get("incrementalCost"),
                          "resolvedBy", str(d, "resolvedBy"))));
          case "travel.disruption.recovery-failed" ->
              record(
                  outcome(
                      tenant,
                      "recovery:" + str(d, "disruptionId"),
                      OutcomeKind.RECOVERY_FAILED,
                      null,
                      null,
                      tripClass(tenant, str(d, "tripId")),
                      blank(str(d, "tripId")),
                      blank(str(d, "orderId")),
                      null,
                      null,
                      str(d, "disruptionId"),
                      event,
                      provenance(
                          "status", str(d, "status"),
                          "stage", str(d, "stage"),
                          "reasonCode", str(d, "reasonCode"))));
          case "travel.optimization.completed" -> decision(tenant, event);
          default -> false;
        };
    return recorded ? Result.RECORDED : Result.IGNORED;
  }

  // ------------------------------------------------------------------ orders

  private boolean orderItems(TenantId tenant, EventEnvelope event, Instant now, boolean change) {
    Map<String, Object> d = event.data();
    String orderId = str(d, "orderId");
    String tripId = str(d, "tripId");
    boolean any = false;
    String orderCurrency =
        d.get("total") instanceof Map<?, ?> total
            ? blank(String.valueOf(total.get("currency")))
            : null;
    for (Map<String, Object> item : items(d)) {
      OrderItemRef ref = itemRef(orderId, tripId, item, orderCurrency);
      trips.upsertItem(tenant, ref, now);
      if (!"CONFIRMED".equals(ref.status())) {
        continue;
      }
      Map<String, Object> prov =
          provenance(
              "externalRef", str(item, "externalRef"),
              "recordLocator", str(item, "recordLocator"),
              "via", change ? "order.changed" : "order.confirmed");
      if (change) {
        prov.put("disruptionId", str(d, "disruptionId"));
      }
      any |=
          record(
              outcome(
                  tenant,
                  "booking:" + orderId + ":" + ref.itemId(),
                  OutcomeKind.BOOKING_CONFIRMED,
                  ref.supplierKey(),
                  ref.provider(),
                  EvidenceClass.ofProvider(ref.provider()),
                  tripId,
                  orderId,
                  ref.itemId(),
                  ref.componentId(),
                  null,
                  event,
                  prov));
    }
    return any;
  }

  private boolean orderFailed(TenantId tenant, EventEnvelope event, Instant now) {
    Map<String, Object> d = event.data();
    String orderId = str(d, "orderId");
    String tripId = str(d, "tripId");
    boolean any = false;
    for (Map<String, Object> item : items(d)) {
      OrderItemRef ref = itemRef(orderId, tripId, item, null);
      trips.upsertItem(tenant, ref, now);
      String code = str(item, "failureCode");
      switch (ref.status()) {
        case "FAILED" -> {
          boolean platform = FailureCodes.isPlatform(code);
          any |=
              record(
                  outcome(
                      tenant,
                      "booking:" + orderId + ":" + ref.itemId(),
                      platform
                          ? OutcomeKind.BOOKING_FAILED_PLATFORM
                          : OutcomeKind.BOOKING_FAILED_SUPPLIER,
                      ref.supplierKey(),
                      ref.provider(),
                      EvidenceClass.ofProvider(ref.provider()),
                      tripId,
                      orderId,
                      ref.itemId(),
                      ref.componentId(),
                      null,
                      event,
                      provenance(
                          "failureCode",
                          code,
                          "orderReasonCode",
                          str(d, "reasonCode"),
                          "classification",
                          platform ? "PLATFORM" : "SUPPLIER")));
        }
        case "CONFIRMED" ->
            any |=
                record(
                    outcome(
                        tenant,
                        "booking:" + orderId + ":" + ref.itemId(),
                        OutcomeKind.BOOKING_CONFIRMED,
                        ref.supplierKey(),
                        ref.provider(),
                        EvidenceClass.ofProvider(ref.provider()),
                        tripId,
                        orderId,
                        ref.itemId(),
                        ref.componentId(),
                        null,
                        event,
                        provenance("via", "order.failed")));
        case "CANCELLED" ->
            any |=
                record(
                    outcome(
                        tenant,
                        "compensation:" + orderId + ":" + ref.itemId(),
                        OutcomeKind.COMPENSATION_RELEASED,
                        ref.supplierKey(),
                        ref.provider(),
                        EvidenceClass.ofProvider(ref.provider()),
                        tripId,
                        orderId,
                        ref.itemId(),
                        ref.componentId(),
                        null,
                        event,
                        provenance("orderReasonCode", str(d, "reasonCode"))));
        case "CANCEL_FAILED" ->
            any |=
                record(
                    outcome(
                        tenant,
                        "compensation:" + orderId + ":" + ref.itemId(),
                        OutcomeKind.COMPENSATION_REFUSED,
                        ref.supplierKey(),
                        ref.provider(),
                        EvidenceClass.ofProvider(ref.provider()),
                        tripId,
                        orderId,
                        ref.itemId(),
                        ref.componentId(),
                        null,
                        event,
                        provenance("failureCode", code, "orderReasonCode", str(d, "reasonCode"))));
        default -> {}
      }
    }
    return any;
  }

  private boolean compensationFailed(TenantId tenant, EventEnvelope event, Instant now) {
    Map<String, Object> d = event.data();
    String orderId = str(d, "orderId");
    String tripId = str(d, "tripId");
    boolean any = false;
    for (Map<String, Object> e : maps(d.get("exposures"))) {
      String itemId = str(e, "itemId");
      if (itemId.isBlank()) {
        continue;
      }
      Optional<OrderItemRef> known = trips.item(tenant, orderId, itemId);
      String provider = blank(str(e, "provider"));
      if (provider == null) {
        provider = known.map(OrderItemRef::provider).orElse(null);
      }
      String key = known.map(OrderItemRef::supplierKey).orElse(null);
      any |=
          record(
              outcome(
                  tenant,
                  "compensation:" + orderId + ":" + itemId,
                  OutcomeKind.COMPENSATION_REFUSED,
                  key,
                  provider,
                  provider == null ? tripClass(tenant, tripId) : EvidenceClass.ofProvider(provider),
                  tripId,
                  orderId,
                  itemId,
                  blank(str(e, "componentId")),
                  null,
                  event,
                  provenance(
                      "exposureId", str(e, "exposureId"),
                      "reason", str(e, "reason"),
                      "amount", e.get("amount"))));
    }
    return any;
  }

  // ------------------------------------------------------------------ trips

  private boolean tripCompleted(TenantId tenant, EventEnvelope event, Instant now) {
    Map<String, Object> d = event.data();
    String tripId = str(d, "tripId");
    String orderId = str(d, "orderId");
    trips.upsert(tenant, tripId, null, blank(orderId), "COMPLETED", null, event.occurredAt(), now);
    boolean any = false;
    for (OrderItemRef item : trips.items(tenant, tripId)) {
      if (!"CONFIRMED".equals(item.status()) && !"CHANGED".equals(item.status())) {
        continue;
      }
      if ("CHANGED".equals(item.status())) {
        continue; // replaced by a later item; the replacement is the one that was delivered
      }
      any |=
          record(
              outcome(
                  tenant,
                  "completion:" + item.orderId() + ":" + item.itemId(),
                  OutcomeKind.TRIP_COMPLETED,
                  item.supplierKey(),
                  item.provider(),
                  EvidenceClass.ofProvider(item.provider()),
                  tripId,
                  item.orderId(),
                  item.itemId(),
                  item.componentId(),
                  null,
                  event,
                  provenance("attestation", "travel.trip.completed")));
    }
    if (!any) {
      log.info("trip {} completed with no confirmed items known here; nothing attributed", tripId);
    }
    return any;
  }

  // ------------------------------------------------------------------ disruptions

  /**
   * Detection is revision 1 (the supplier told us); impact confirmation is revision 2 of the same
   * outcome, now tied to the trip and order. One logical outcome, two revisions, counted once.
   */
  private boolean disruptionDetected(TenantId tenant, EventEnvelope event, int revision) {
    Map<String, Object> d = event.data();
    String supplier = str(d, "supplier");
    String supplierEventId = str(d, "supplierEventId");
    @SuppressWarnings("unchecked")
    Map<String, Object> affected =
        d.get("affected") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    String carrier = str(affected, "carrier");
    String key = carrier.isBlank() ? null : "air:" + carrier;
    // The disruption id is minted by the gateway at detection and carried by every later disruption
    // event; the supplier's own event id is provenance (impact confirmation does not repeat it).
    String logical = "disruption:" + str(d, "disruptionId");
    if (revision == 2 && outcomes.currentRevision(tenant, logical) == 0) {
      // Confirmation arrived before (or without) the detection we saw: still one outcome.
      revision = 1;
    }
    Outcome o =
        outcome(
            tenant,
            logical,
            OutcomeKind.SUPPLIER_DISRUPTION,
            key,
            supplier.isBlank() ? null : supplier,
            supplier.isBlank() ? properties.deploymentClass() : EvidenceClass.ofProvider(supplier),
            blank(str(d, "tripId")),
            blank(str(d, "orderId")),
            null,
            blank(str(affected, "componentId")),
            str(d, "disruptionId"),
            event,
            provenance(
                "type", str(d, "type"),
                "severity", str(d, "severity"),
                "flightNumber", str(affected, "flightNumber"),
                "supplierEventId", supplierEventId));
    return record(withRevision(o, revision));
  }

  // ------------------------------------------------------------------ decisions

  private boolean decision(TenantId tenant, EventEnvelope event) {
    Map<String, Object> d = event.data();
    List<Map<String, Object>> candidates = maps(d.get("candidates"));
    String selected = blank(str(d, "selectedBundleId"));
    List<String> selectedKeys = new ArrayList<>();
    EvidenceClass evidenceClass = properties.deploymentClass();
    boolean sawProvider = false;
    for (Map<String, Object> c : candidates) {
      String provider = str(c, "provider");
      if (!provider.isBlank()) {
        EvidenceClass cls = EvidenceClass.ofProvider(provider);
        evidenceClass = sawProvider && cls != evidenceClass ? EvidenceClass.LIVE : cls;
        sawProvider = true;
      }
      if (selected != null && selected.equals(str(c, "candidateId"))) {
        selectedKeys.addAll(strings(c.get("supplierKeys")));
      }
    }
    @SuppressWarnings("unchecked")
    Map<String, Object> learning =
        d.get("learning") instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    boolean fresh =
        decisions.insert(
            new Decision(
                str(d, "optimizationRunId"),
                tenant,
                str(d, "tripId"),
                evidenceClass,
                event.occurredAt(),
                selected,
                selectedKeys,
                candidates,
                learning,
                event.eventId()));
    metrics.decision(fresh ? "RECORDED" : "DUPLICATE");
    return fresh;
  }

  private boolean financeRefund(TenantId tenant, EventEnvelope event) {
    Map<String, Object> d = event.data();
    String orderId = str(d, "orderId");
    String itemId = d.get("itemId") == null ? null : str(d, "itemId");
    String tripId = str(d, "tripId");
    String key = "refund:" + orderId + ":" + (itemId == null ? "order" : itemId);
    Optional<Outcome> current = outcomes.current(tenant, key);
    @SuppressWarnings("unchecked")
    Map<String, Object> refunded = (Map<String, Object>) d.get("refundedTotal");
    Map<String, Object> provenance =
        provenance(
            "amountMinor",
            refunded == null ? null : refunded.get("amountMinor"),
            "currency",
            refunded == null ? null : refunded.get("currency"),
            "reference",
            str(d, "paymentId"),
            "recordedBy",
            "finance-ledger");
    if (current.isPresent()
        && java.util.Objects.equals(
            String.valueOf(current.get().provenance().get("amountMinor")),
            String.valueOf(provenance.get("amountMinor")))) {
      return false;
    }
    Optional<OrderItemRef> item =
        itemId == null ? Optional.empty() : trips.item(tenant, orderId, itemId);
    String provider = item.map(OrderItemRef::provider).orElse(null);
    Outcome o =
        outcome(
            tenant,
            key,
            OutcomeKind.REFUND_SETTLED,
            item.map(OrderItemRef::supplierKey).orElse(null),
            provider,
            provider == null ? tripClass(tenant, tripId) : EvidenceClass.ofProvider(provider),
            tripId,
            orderId,
            itemId,
            item.map(OrderItemRef::componentId).orElse(null),
            null,
            event,
            provenance);
    return record(withRevision(o, current.map(c -> c.revision() + 1).orElse(1)));
  }

  // ------------------------------------------------------------------ helpers

  private boolean record(Outcome o) {
    boolean fresh = outcomes.insert(o);
    if (fresh) {
      metrics.outcome(
          o.kind().name(), o.evidenceClass().name(), o.revision() > 1 ? "REVISED" : "RECORDED");
    } else {
      metrics.outcome(o.kind().name(), o.evidenceClass().name(), "DUPLICATE_REPRESENTATION");
      log.info(
          "outcome {} rev {} already recorded; {} is a repeated representation",
          o.logicalKey(),
          o.revision(),
          o.sourceRef());
    }
    return fresh;
  }

  private Outcome outcome(
      TenantId tenant,
      String logicalKey,
      OutcomeKind kind,
      @Nullable String supplierKey,
      @Nullable String provider,
      EvidenceClass evidenceClass,
      @Nullable String tripId,
      @Nullable String orderId,
      @Nullable String itemId,
      @Nullable String componentId,
      @Nullable String disruptionId,
      EventEnvelope event,
      Map<String, Object> provenance) {
    String traveler =
        tripId == null ? null : trips.find(tenant, tripId).map(t -> t.travelerId()).orElse(null);
    return new Outcome(
        Ids.newId(IdPrefix.OUTCOME),
        tenant,
        logicalKey,
        1,
        kind,
        kind.quality(),
        supplierKey,
        provider,
        evidenceClass,
        tripId,
        orderId,
        itemId,
        componentId,
        disruptionId,
        traveler,
        event.occurredAt(),
        clock.instant(),
        "EVENT",
        event.eventId() + " " + event.eventType(),
        provenance);
  }

  private static Outcome withRevision(Outcome o, int revision) {
    return new Outcome(
        o.outcomeId(),
        o.tenant(),
        o.logicalKey(),
        revision,
        o.kind(),
        o.quality(),
        o.supplierKey(),
        o.provider(),
        o.evidenceClass(),
        o.tripId(),
        o.orderId(),
        o.itemId(),
        o.componentId(),
        o.disruptionId(),
        o.travelerId(),
        o.observedAt(),
        o.recordedAt(),
        o.source(),
        o.sourceRef(),
        o.provenance());
  }

  private EvidenceClass tripClass(TenantId tenant, String tripId) {
    for (OrderItemRef item : trips.items(tenant, tripId)) {
      if (item.provider() != null) {
        return EvidenceClass.ofProvider(item.provider());
      }
    }
    return properties.deploymentClass();
  }

  private static OrderItemRef itemRef(
      String orderId, String tripId, Map<String, Object> item, @Nullable String orderCurrency) {
    String itemId = str(item, "itemId");
    String currency =
        item.get("total") instanceof Map<?, ?> total
            ? blank(String.valueOf(total.get("currency")))
            : null;
    return new OrderItemRef(
        orderId,
        itemId,
        tripId,
        blank(str(item, "componentId")),
        str(item, "type").isBlank() ? "AIR" : str(item, "type"),
        blank(str(item, "provider")),
        blank(str(item, "supplierKey")),
        str(item, "status").isBlank() ? "PENDING" : str(item, "status"),
        currency == null ? orderCurrency : currency);
  }

  static Map<String, Object> provenance(Object... kv) {
    Map<String, Object> m = new LinkedHashMap<>();
    for (int i = 0; i + 1 < kv.length; i += 2) {
      Object v = kv[i + 1];
      if (v != null && !(v instanceof String s && s.isBlank())) {
        m.put(String.valueOf(kv[i]), v);
      }
    }
    return m;
  }

  static String str(Map<String, Object> m, String key) {
    Object v = m.get(key);
    return v == null ? "" : String.valueOf(v);
  }

  static @Nullable String blank(String s) {
    return s == null || s.isBlank() ? null : s;
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> items(Map<String, Object> d) {
    return maps(d.get("items"));
  }

  @SuppressWarnings("unchecked")
  static List<Map<String, Object>> maps(@Nullable Object value) {
    if (!(value instanceof List<?> list)) {
      return List.of();
    }
    List<Map<String, Object>> out = new ArrayList<>();
    for (Object o : list) {
      if (o instanceof Map<?, ?> m) {
        out.add((Map<String, Object>) m);
      }
    }
    return out;
  }

  static List<String> strings(@Nullable Object value) {
    if (!(value instanceof List<?> list)) {
      return List.of();
    }
    List<String> out = new ArrayList<>();
    for (Object o : list) {
      if (o != null) {
        out.add(String.valueOf(o));
      }
    }
    return out;
  }
}
