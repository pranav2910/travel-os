package io.travelos.disruption.service;

import com.google.protobuf.util.JsonFormat;
import io.grpc.StatusRuntimeException;
import io.travelos.common.ids.IdPrefix;
import io.travelos.common.ids.Ids;
import io.travelos.common.tenant.TenantId;
import io.travelos.contracts.disruption.v1.AffectedSegment;
import io.travelos.contracts.offer.v1.FlightSegment;
import io.travelos.contracts.order.v1.Order;
import io.travelos.contracts.order.v1.OrderItem;
import io.travelos.contracts.order.v1.OrderItemStatus;
import io.travelos.contracts.order.v1.OrderStatus;
import io.travelos.disruption.events.DisruptionEvents;
import io.travelos.disruption.ingest.DisruptionIngestService;
import io.travelos.disruption.metrics.RecoveryMetrics;
import io.travelos.disruption.model.Disruption;
import io.travelos.disruption.model.DisruptionStatus;
import io.travelos.disruption.orders.OrderLookup;
import io.travelos.disruption.store.DisruptionRepository;
import io.travelos.spring.outbox.Outbox;
import io.travelos.spring.web.auth.RequestPrincipal;
import io.travelos.spring.web.error.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Phase 6: a traveler asks to move a booked flight ("I need to fly Wednesday instead"). The request
 * becomes a disruption of type TRAVELER_REQUEST tied to the order the Order service confirms,
 * IMPACT_CONFIRMED at once, so the same recovery does the work: search the window the traveler
 * asked for, policy judges the change with the person as the actor (the approval threshold, not the
 * agent's autonomy), a manager approves when policy says so, the Order service changes the booking
 * once. Idempotent by the caller's key: the same request twice is one disruption.
 */
@Service
public class ChangeRequestService {
  private static final Logger log = LoggerFactory.getLogger(ChangeRequestService.class);
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final JsonFormat.Printer PRINTER =
      JsonFormat.printer().omittingInsignificantWhitespace();
  static final String SUPPLIER_EVENT_PREFIX = "traveler-request:";

  public record ChangeRequest(
      String tripId,
      String orderId,
      String componentId,
      Instant notBefore,
      @Nullable Instant notAfter,
      @Nullable String reason) {}

  private final DisruptionRepository repository;
  private final OrderLookup orders;
  private final Outbox outbox;
  private final RecoveryMetrics metrics;
  private final Clock clock;

  public ChangeRequestService(
      DisruptionRepository repository,
      OrderLookup orders,
      Outbox outbox,
      RecoveryMetrics metrics,
      Clock clock) {
    this.repository = repository;
    this.orders = orders;
    this.outbox = outbox;
    this.metrics = metrics;
    this.clock = clock;
  }

  @Transactional
  public Disruption request(RequestPrincipal me, ChangeRequest request, String idempotencyKey) {
    TenantId tenant = me.tenant();
    String supplierEventId = SUPPLIER_EVENT_PREFIX + me.principal().id() + ":" + idempotencyKey;
    Optional<Order> order;
    try {
      order = orders.byId(tenant.value(), request.tripId(), request.orderId());
    } catch (StatusRuntimeException e) {
      if (DisruptionIngestService.isTransient(e.getStatus())) {
        throw new ApiException.Conflict(
            "ORDER_SERVICE_UNAVAILABLE", "the order could not be read right now; try again");
      }
      throw e;
    }
    // the order is the truth about who may ask: the traveler it belongs to, or a travel admin
    Order o =
        order
            .filter(x -> x.getTripId().equals(request.tripId()))
            .orElseThrow(() -> new ApiException.NotFound("order", request.orderId()));
    boolean traveler = o.getTravelerId().equals(me.employeeId());
    if (!traveler && !me.hasRole("TRAVEL_ADMIN")) {
      throw new ApiException.NotFound("order", request.orderId());
    }
    Optional<Disruption> existing =
        repository.findBySupplierEvent(tenant, "traveler", supplierEventId);
    if (existing.isPresent()) {
      return existing.get();
    }
    if (o.getStatus() != OrderStatus.CONFIRMED && o.getStatus() != OrderStatus.CHANGED) {
      throw new ApiException.Conflict(
          "ORDER_NOT_CHANGEABLE", "order " + o.getOrderId() + " is " + o.getStatus());
    }
    OrderItem item =
        o.getItemsList().stream()
            .filter(
                i ->
                    i.getComponentId().equals(request.componentId())
                        || i.getItemId().equals(request.componentId()))
            .reduce((a, b) -> b)
            .orElseThrow(() -> new ApiException.NotFound("component", request.componentId()));
    if (item.getStatus() != OrderItemStatus.ITEM_CONFIRMED) {
      throw new ApiException.Conflict(
          "COMPONENT_NOT_CONFIRMED",
          "component " + request.componentId() + " is " + item.getStatus());
    }
    if (!item.getOffer().hasAir()
        || item.getOffer().getAir().getOutbound().getSegmentsCount() == 0) {
      throw new ApiException.Unprocessable(
          "COMPONENT_NOT_RETIMEABLE",
          "only a flight can be moved this way; " + request.componentId() + " is not one");
    }
    Instant now = clock.instant();
    if (!request.notBefore().isAfter(now)) {
      throw new ApiException.Unprocessable(
          "WINDOW_IN_PAST", "the requested window starts in the past");
    }
    if (request.notAfter() != null && !request.notAfter().isAfter(request.notBefore())) {
      throw new ApiException.Unprocessable("WINDOW_INVALID", "notAfter must be after notBefore");
    }
    FlightSegment first = item.getOffer().getAir().getOutbound().getSegments(0);
    String segmentId = first.getFlightNumber() + "-" + first.getOrigin();
    AffectedSegment.Builder affected =
        AffectedSegment.newBuilder()
            .setSegmentId(segmentId)
            .setCarrier(first.getCarrier())
            .setFlightNumber(first.getFlightNumber())
            .setOrigin(first.getOrigin())
            .setDestination(first.getDestination())
            .setScheduledDeparture(first.getDeparture())
            .setComponentId(item.getComponentId())
            .setRequestedNotBefore(ts(request.notBefore()))
            .setRequestedBy(me.principal().id());
    if (first.hasArrival()) {
      affected.setScheduledArrival(first.getArrival());
    }
    if (request.notAfter() != null) {
      affected.setRequestedNotAfter(ts(request.notAfter()));
    }
    String affectedJson = print(affected.build());
    String disruptionId = Ids.newId(IdPrefix.DISRUPTION);
    Disruption detected =
        new Disruption(
            disruptionId,
            tenant,
            null,
            null,
            null,
            segmentId,
            "TRAVELER_REQUEST",
            "traveler",
            supplierEventId,
            item.getExternalRef().isBlank() ? o.getExternalOrderId() : item.getExternalRef(),
            item.getRecordLocator().isBlank() ? null : item.getRecordLocator(),
            now,
            DisruptionStatus.DETECTED,
            "LOW",
            null,
            request.reason() == null || request.reason().isBlank()
                ? "traveler asked to move the flight"
                : request.reason(),
            affectedJson,
            "{}",
            null,
            null,
            "req:" + idempotencyKey,
            0,
            now,
            now);
    repository.insert(detected);
    metrics.detected();
    repository.transition(
        detected,
        DisruptionStatus.IMPACT_CONFIRMED,
        "traveler request by " + me.principal().id() + " on order " + o.getOrderId(),
        new DisruptionRepository.Impact(
            o.getTripId(), o.getOrderId(), o.getTravelerId(), segmentId),
        null,
        null,
        null,
        now);
    Disruption confirmed = repository.find(tenant, disruptionId).orElseThrow();
    outbox.append(
        DisruptionEvents.impactConfirmed(confirmed, affectedMap(affectedJson), null, clock));
    log.info(
        "traveler request {} by {}: move {} of order {} to {}..{}",
        disruptionId,
        me.principal().id(),
        segmentId,
        o.getOrderId(),
        request.notBefore(),
        request.notAfter());
    return confirmed;
  }

  private static com.google.protobuf.Timestamp ts(Instant i) {
    return com.google.protobuf.Timestamp.newBuilder()
        .setSeconds(i.getEpochSecond())
        .setNanos(i.getNano())
        .build();
  }

  private static String print(com.google.protobuf.Message m) {
    try {
      return PRINTER.print(m);
    } catch (com.google.protobuf.InvalidProtocolBufferException e) {
      throw new IllegalStateException(e);
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> affectedMap(String json) {
    return new LinkedHashMap<>(JSON.readValue(json, Map.class));
  }
}
