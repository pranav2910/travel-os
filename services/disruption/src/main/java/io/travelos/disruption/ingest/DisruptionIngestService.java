package io.travelos.disruption.ingest;

import com.google.protobuf.util.JsonFormat;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.travelos.common.ids.IdPrefix;
import io.travelos.common.ids.Ids;
import io.travelos.common.tenant.TenantId;
import io.travelos.contracts.disruption.v1.AffectedSegment;
import io.travelos.contracts.disruption.v1.RecoveryOutcome;
import io.travelos.contracts.order.v1.Order;
import io.travelos.disruption.events.DisruptionEvents;
import io.travelos.disruption.metrics.RecoveryMetrics;
import io.travelos.disruption.model.Disruption;
import io.travelos.disruption.model.DisruptionStatus;
import io.travelos.disruption.orders.OrderLookup;
import io.travelos.disruption.store.DisruptionRepository;
import io.travelos.events.EventEnvelope;
import io.travelos.spring.outbox.Outbox;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * travel.disruption.detected in, a Disruption tied to its trip and order out (IMPACT_CONFIRMED), or
 * MANUAL_INTERVENTION_REQUIRED when the reference is not ours. Exactly once per event id and per
 * supplier event: Kafka redeliveries and duplicate supplier webhooks both collapse here.
 */
@Service
public class DisruptionIngestService {

  private static final Logger log = LoggerFactory.getLogger(DisruptionIngestService.class);
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final JsonFormat.Printer PRINTER =
      JsonFormat.printer().omittingInsignificantWhitespace();

  public enum Outcome {
    CONFIRMED,
    NO_IMPACT,
    DUPLICATE,
    /**
     * Recorded as DETECTED; the Order service could not be asked right now (retried by the
     * scheduler).
     */
    PENDING
  }

  private final DisruptionRepository repository;
  private final OrderLookup orders;
  private final Outbox outbox;
  private final RecoveryMetrics metrics;
  private final Clock clock;
  private final TransactionTemplate tx;

  public DisruptionIngestService(
      DisruptionRepository repository,
      OrderLookup orders,
      Outbox outbox,
      RecoveryMetrics metrics,
      Clock clock,
      PlatformTransactionManager transactionManager) {
    this.repository = repository;
    this.orders = orders;
    this.outbox = outbox;
    this.metrics = metrics;
    this.clock = clock;
    this.tx = new TransactionTemplate(transactionManager);
  }

  /**
   * Two transactions on purpose: the disruption is recorded (DETECTED) and the Kafka offset can be
   * committed even when the Order service is unreachable; impact confirmation then runs in its own
   * transaction, here or from the scheduler that retries every DETECTED row. A dependency outage
   * therefore delays a recovery; it never drops a supplier notice or blocks the partition.
   */
  public Outcome ingest(EventEnvelope event) {
    Outcome recorded = tx.execute(status -> record(event));
    if (recorded != null) {
      return recorded;
    }
    return confirmImpact(
        TenantId.of(event.tenantId()), str(event.data(), "disruptionId"), event.eventId());
  }

  /** Returns null when a new DETECTED row was inserted and impact must still be confirmed. */
  private @Nullable Outcome record(EventEnvelope event) {
    Instant now = clock.instant();
    if (!repository.markProcessed(event.eventId(), event.eventType(), now)) {
      metrics.duplicate();
      log.info("event {} already processed", event.eventId());
      return Outcome.DUPLICATE;
    }
    Map<String, Object> data = event.data();
    TenantId tenant = TenantId.of(event.tenantId());
    String supplier = str(data, "supplier");
    String supplierEventId = str(data, "supplierEventId");
    Optional<Disruption> existing =
        repository.findBySupplierEvent(tenant, supplier, supplierEventId);
    if (existing.isPresent()) {
      metrics.duplicate();
      log.info(
          "supplier event {}/{} already known as {}",
          supplier,
          supplierEventId,
          existing.get().disruptionId());
      return Outcome.DUPLICATE;
    }
    metrics.detected();
    @SuppressWarnings("unchecked")
    Map<String, Object> affected = (Map<String, Object>) data.getOrDefault("affected", Map.of());
    String disruptionId = str(data, "disruptionId");
    if (disruptionId.isBlank()) {
      disruptionId = Ids.newId(IdPrefix.DISRUPTION);
    }
    Disruption detected =
        new Disruption(
            disruptionId,
            tenant,
            null,
            null,
            null,
            str(affected, "segmentId").isBlank() ? null : str(affected, "segmentId"),
            str(data, "type"),
            supplier,
            supplierEventId,
            str(data, "externalOrderId"),
            str(data, "recordLocator").isBlank() ? null : str(data, "recordLocator"),
            parseInstant(str(data, "detectedAt"), event.occurredAt()),
            DisruptionStatus.DETECTED,
            str(data, "severity").isBlank() ? "MEDIUM" : str(data, "severity"),
            str(data, "rawReference").isBlank() ? null : str(data, "rawReference"),
            str(data, "reason").isBlank() ? null : str(data, "reason"),
            affectedJson(affected),
            "{}",
            null,
            null,
            event.eventId(),
            0,
            now,
            now);
    repository.insert(detected);
    return null;
  }

  /** Every DETECTED row the scheduler finds gets another try, in order of detection. */
  public int confirmPending(int limit) {
    int confirmed = 0;
    for (Disruption d : repository.byStatus(DisruptionStatus.DETECTED, limit)) {
      try {
        if (confirmImpact(d.tenant(), d.disruptionId(), d.sourceEventId()) != Outcome.PENDING) {
          confirmed++;
        }
      } catch (RuntimeException e) {
        log.warn("impact retry for {} failed: {}", d.disruptionId(), e.getMessage());
      }
    }
    return confirmed;
  }

  /**
   * Impacted-trip detection: the Order service is the truth about what we booked. A transient
   * failure leaves the row DETECTED (PENDING); NOT_FOUND ends it in MANUAL_INTERVENTION_REQUIRED.
   */
  public Outcome confirmImpact(TenantId tenant, String disruptionId, String causationId) {
    Disruption detected = repository.find(tenant, disruptionId).orElse(null);
    if (detected == null || detected.status() != DisruptionStatus.DETECTED) {
      return detected == null ? Outcome.NO_IMPACT : Outcome.CONFIRMED;
    }
    Optional<Order> order;
    try {
      order =
          orders.byExternalRef(
              tenant.value(), disruptionId, detected.supplier(), detected.externalOrderId());
    } catch (StatusRuntimeException e) {
      if (isTransient(e.getStatus())) {
        log.warn(
            "disruption {}: order service unavailable ({}); impact confirmation deferred",
            disruptionId,
            e.getStatus().getCode());
        return Outcome.PENDING;
      }
      throw e;
    }
    Optional<Order> found = order;
    Outcome outcome = tx.execute(status -> confirm(detected, found, causationId));
    return outcome == null ? Outcome.PENDING : outcome;
  }

  private Outcome confirm(Disruption detected, Optional<Order> order, String causationId) {
    Instant now = clock.instant();
    TenantId tenant = detected.tenant();
    String disruptionId = detected.disruptionId();
    String supplier = detected.supplier();
    Map<String, Object> affected = affectedMap(detected.affectedJson());
    if (order.isEmpty()) {
      log.warn(
          "disruption {}: {} reference {} is not one of our orders",
          disruptionId,
          supplier,
          detected.externalOrderId());
      repository.transition(
          detected,
          DisruptionStatus.MANUAL_INTERVENTION_REQUIRED,
          "no order for the supplier reference",
          null,
          null,
          "IMPACT",
          "ORDER_NOT_FOUND",
          now);
      Disruption unmatched = repository.find(tenant, disruptionId).orElseThrow();
      repository.insertOutcome(
          Ids.newId(IdPrefix.RECOVERY_OUTCOME),
          unmatched,
          DisruptionStatus.MANUAL_INTERVENTION_REQUIRED,
          print(
              RecoveryOutcome.newBuilder()
                  .setDisruptionId(disruptionId)
                  .setStatus(
                      io.travelos.contracts.disruption.v1.DisruptionStatus
                          .MANUAL_INTERVENTION_REQUIRED)
                  .setFailureStage("IMPACT")
                  .setFailureCode("ORDER_NOT_FOUND")
                  .setMessage(
                      "no order for " + supplier + " reference " + detected.externalOrderId())
                  .build()),
          now);
      metrics.failed();
      outbox.append(
          DisruptionEvents.recoveryFailed(
              unmatched,
              "MANUAL_INTERVENTION_REQUIRED",
              "IMPACT",
              "ORDER_NOT_FOUND",
              "no order for " + supplier + " reference " + detected.externalOrderId(),
              clock));
      return Outcome.NO_IMPACT;
    }
    Order o = order.get();
    repository.transition(
        detected,
        DisruptionStatus.IMPACT_CONFIRMED,
        "order " + o.getOrderId() + " on trip " + o.getTripId(),
        new DisruptionRepository.Impact(
            o.getTripId(), o.getOrderId(), o.getTravelerId(), detected.segmentId()),
        null,
        null,
        null,
        now);
    Disruption confirmed = repository.find(tenant, disruptionId).orElseThrow();
    outbox.append(DisruptionEvents.impactConfirmed(confirmed, affected, causationId, clock));
    log.info(
        "disruption {} ({} {}) hits order {} of trip {}",
        disruptionId,
        confirmed.type(),
        str(affected, "flightNumber"),
        o.getOrderId(),
        o.getTripId());
    return Outcome.CONFIRMED;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> affectedMap(String json) {
    try {
      return JSON.readValue(json, Map.class);
    } catch (RuntimeException e) {
      return Map.of();
    }
  }

  static boolean isTransient(Status status) {
    return switch (status.getCode()) {
      case UNAVAILABLE, DEADLINE_EXCEEDED, RESOURCE_EXHAUSTED, ABORTED, UNKNOWN, INTERNAL -> true;
      default -> false;
    };
  }

  private static String affectedJson(Map<String, Object> affected) {
    // Validate through the proto shape so what we store is what the API returns.
    AffectedSegment.Builder b = AffectedSegment.newBuilder();
    try {
      JsonFormat.parser().ignoringUnknownFields().merge(JSON.writeValueAsString(affected), b);
    } catch (Exception e) {
      Map<String, Object> copy = new LinkedHashMap<>(affected);
      copy.remove("scheduledDeparture");
      copy.remove("scheduledArrival");
      try {
        JsonFormat.parser().ignoringUnknownFields().merge(JSON.writeValueAsString(copy), b);
      } catch (Exception ignored) {
        // keep whatever parsed
      }
    }
    return print(b.build());
  }

  private static Instant parseInstant(String value, Instant fallback) {
    try {
      return value.isBlank() ? fallback : Instant.parse(value);
    } catch (RuntimeException e) {
      return fallback;
    }
  }

  private static String print(com.google.protobuf.Message m) {
    try {
      return PRINTER.print(m);
    } catch (com.google.protobuf.InvalidProtocolBufferException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String str(Map<String, Object> m, String key) {
    Object v = m.get(key);
    return v == null ? "" : String.valueOf(v);
  }
}
