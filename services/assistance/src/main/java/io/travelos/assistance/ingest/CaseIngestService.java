package io.travelos.assistance.ingest;

import io.travelos.assistance.model.CaseKind;
import io.travelos.assistance.model.Priority;
import io.travelos.assistance.model.Queue;
import io.travelos.assistance.service.CaseService;
import io.travelos.assistance.store.ProcessedEventRepository;
import io.travelos.assistance.store.TripIndexRepository;
import io.travelos.common.tenant.TenantId;
import io.travelos.events.EventEnvelope;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns what the platform announced into cases: exactly once per event id (Kafka redelivery), one
 * open case per fact (the same exposure told by two events is one case). A settled fact (an
 * exposure resolved, a trip cancelled, a disruption resolved) resolves its case by the system. The
 * event producers stay authoritative: a case never changes an order, a trip or a payment.
 */
@Service
public class CaseIngestService {
  public enum Result {
    OPENED,
    SETTLED,
    INDEXED,
    DUPLICATE_EVENT,
    IGNORED
  }

  private static final String PRODUCER = "service/assistance";

  private final ProcessedEventRepository processed;
  private final TripIndexRepository trips;
  private final CaseService cases;
  private final io.travelos.assistance.notify.NotificationRules notifications;
  private final Clock clock;

  public CaseIngestService(
      ProcessedEventRepository processed,
      TripIndexRepository trips,
      CaseService cases,
      io.travelos.assistance.notify.NotificationRules notifications,
      Clock clock) {
    this.processed = processed;
    this.trips = trips;
    this.cases = cases;
    this.notifications = notifications;
    this.clock = clock;
  }

  @Transactional
  public Result ingest(EventEnvelope event) {
    Instant now = clock.instant();
    if (!processed.markProcessed(event.eventId(), event.eventType(), now)) {
      return Result.DUPLICATE_EVENT;
    }
    TenantId tenant = TenantId.of(event.tenantId());
    Map<String, Object> d = event.data();
    String tripId = str(d.get("tripId"));
    String orderId = str(d.get("orderId"));
    if (tripId != null) {
      // Phase 8: the index learns who travels, where and when, from what the events say
      String status =
          switch (event.eventType()) {
            case "travel.trip.booked" -> "BOOKED";
            case "travel.trip.cancelled" -> "CANCELLED";
            case "travel.trip.failed" -> "FAILED";
            case "travel.trip.completed" -> "COMPLETED";
            case "travel.trip.created" -> "SUBMITTED";
            default -> null;
          };
      java.util.List<String> cities = null;
      if (d.get("cities") instanceof java.util.List<?> l) {
        cities = l.stream().map(String::valueOf).toList();
      }
      trips.upsert(
          tenant,
          tripId,
          str(d.get("travelerId")),
          orderId,
          new TripIndexRepository.Journey(
              str(d.get("travelerEmail")),
              str(d.get("travelerName")),
              status,
              str(d.get("origin")),
              str(d.get("destination")),
              instant(d.get("departsAt")),
              instant(d.get("returnsAt")),
              cities),
          now);
    }
    Result result = route(event, tenant, d, tripId, orderId);
    // Phase 8: what people should hear about, in the same transaction as the fact
    notifications.apply(event);
    return result;
  }

  private static java.time.@Nullable Instant instant(@Nullable Object value) {
    String s = str(value);
    if (s == null) {
      return null;
    }
    try {
      return Instant.parse(s);
    } catch (RuntimeException e) {
      return null;
    }
  }

  private Result route(
      EventEnvelope event,
      TenantId tenant,
      Map<String, Object> d,
      @Nullable String tripId,
      @Nullable String orderId) {
    return switch (event.eventType()) {
      case "travel.order.compensation-failed" ->
          exposures(
              event,
              tenant,
              tripId,
              orderId,
              list(d.get("exposures")),
              "the saga could not undo a booking");
      case "travel.order.items-released" -> {
        Result r =
            exposures(
                event,
                tenant,
                tripId,
                orderId,
                list(d.get("refused")),
                "a component's release was refused");
        yield r;
      }
      case "travel.order.exposure-resolved" ->
          settled(
              tenant,
              "exposure:" + str(d.get("exposureId")),
              "exposure resolved by " + str(d.get("resolvedBy")) + ": " + str(d.get("resolution")),
              event);
      case "travel.order.failed" -> orderFailed(event, tenant, tripId, orderId);
      case "travel.trip.cancellation-incomplete" ->
          opened(
              event,
              tenant,
              CaseKind.CANCELLATION_INCOMPLETE,
              Priority.HIGH,
              Queue.TRAVEL_OPS,
              "Cancellation of trip " + tripId + " is incomplete",
              str(d.get("message")),
              tripId,
              orderId,
              null,
              null,
              null,
              "cancel-incomplete:" + tripId,
              "Release the remaining reservation with the supplier by phone, then resolve the order's exposure so the trip can finish cancelling");
      case "travel.trip.cancelled" ->
          settled(tenant, "cancel-incomplete:" + tripId, "trip cancelled", event);
      case "travel.trip.components-released" ->
          componentsReleased(event, tenant, tripId, orderId, list(d.get("components")));
      case "travel.trip.failed" ->
          opened(
              event,
              tenant,
              CaseKind.BOOKING_FAILED,
              Priority.NORMAL,
              Queue.TRAVEL_OPS,
              "Trip " + tripId + " failed at " + str(d.get("stage")),
              str(d.get("reasonCode")) + ": " + str(d.get("message")),
              tripId,
              orderId,
              null,
              null,
              null,
              "trip-failed:" + tripId,
              "Tell the traveler; re-plan the trip with them or confirm it is abandoned");
      case "travel.approval.escalated" ->
          opened(
              event,
              tenant,
              CaseKind.APPROVAL_ESCALATED,
              Priority.HIGH,
              Queue.APPROVALS,
              "Approval step " + str(d.get("step")) + " for trip " + tripId + " went unanswered",
              str(d.get("reason")),
              tripId,
              null,
              null,
              null,
              null,
              "approval:" + str(d.get("approvalId")),
              "Decide the approval (POST /api/v1/trips/"
                  + tripId
                  + "/approval) or find the "
                  + str(d.get("role"))
                  + " who should",
              "TRAVEL_ADMIN");
      case "travel.approval.approved", "travel.approval.rejected", "travel.approval.expired" ->
          settled(
              tenant,
              "approval:" + str(d.get("approvalId")),
              "approval "
                  + event.eventType().substring("travel.approval.".length())
                  + (d.get("decidedBy") == null ? "" : " by " + str(d.get("decidedBy"))),
              event);
      case "travel.disruption.approval-required" ->
          opened(
              event,
              tenant,
              CaseKind.RECOVERY_APPROVAL,
              Priority.CRITICAL,
              Queue.APPROVALS,
              "Recovery for trip " + tripId + " waits on a " + str(d.get("role")) + " approval",
              "incremental cost "
                  + money(d.get("incrementalCost"))
                  + "; reasons "
                  + str(d.get("reasonCodes")),
              tripId,
              orderId,
              str(d.get("disruptionId")),
              null,
              null,
              "recovery-approval:" + str(d.get("disruptionId")),
              "Get the approval decided now (approval "
                  + str(d.get("approvalId"))
                  + "); the traveler is affected while it waits",
              str(d.get("role")));
      case "travel.disruption.recovery-failed" ->
          opened(
              event,
              tenant,
              CaseKind.RECOVERY_FAILED,
              Priority.CRITICAL,
              Queue.TRAVEL_OPS,
              "Automated recovery for trip " + tripId + " found nothing",
              str(d.get("reasonCode")) + ": " + str(d.get("message")),
              tripId,
              orderId,
              str(d.get("disruptionId")),
              null,
              null,
              "recovery-failed:" + str(d.get("disruptionId")),
              "Rebook the traveler by hand with the supplier; record the new reservation on the order");
      case "travel.disruption.resolved" ->
          settledPrefix(
              tenant,
              "recovery-",
              str(d.get("disruptionId")),
              "disruption resolved by " + str(d.get("resolvedBy")),
              event);
      case "travel.finance.payment-declined" ->
          opened(
              event,
              tenant,
              CaseKind.PAYMENT_DECLINED,
              Priority.HIGH,
              Queue.FINANCE,
              "Payment declined for order " + orderId,
              str(d.get("reasonCode"))
                  + ": "
                  + str(d.get("message"))
                  + " ("
                  + money(d.get("amount"))
                  + ")",
              tripId,
              orderId,
              null,
              null,
              null,
              "payment-declined:" + orderId,
              "Arrange another instrument with the traveler and re-authorize the order");
      case "travel.finance.payment-authorized", "travel.finance.payment-captured" ->
          settled(
              tenant,
              "payment-declined:" + orderId,
              "payment " + str(d.get("status")).toLowerCase(java.util.Locale.ROOT),
              event);
      default -> tripId != null ? Result.INDEXED : Result.IGNORED;
    };
  }

  private Result exposures(
      EventEnvelope event,
      TenantId tenant,
      @Nullable String tripId,
      @Nullable String orderId,
      List<Map<String, Object>> exposures,
      String why) {
    Result result = Result.IGNORED;
    for (Map<String, Object> x : exposures) {
      if (!"OPEN".equals(str(x.get("status")))) {
        continue;
      }
      String exposureId = str(x.get("exposureId"));
      Result r =
          opened(
              event,
              tenant,
              CaseKind.EXPOSURE,
              Priority.HIGH,
              Queue.FINANCE,
              money(x.get("amount"))
                  + " at risk with "
                  + str(x.get("provider"))
                  + " ("
                  + str(x.get("externalRef"))
                  + ")",
              why
                  + ": "
                  + str(x.get("reason"))
                  + (x.get("detail") == null ? "" : " - " + str(x.get("detail"))),
              tripId,
              orderId,
              null,
              exposureId,
              str(x.get("componentId")),
              "exposure:" + exposureId,
              "Release "
                  + str(x.get("externalRef"))
                  + " with "
                  + str(x.get("provider"))
                  + " by phone or portal, then record the resolution on the order (POST /api/v1/orders/"
                  + orderId
                  + "/exposures/"
                  + exposureId
                  + "/resolution)",
              "TRAVEL_ADMIN");
      if (r == Result.OPENED) {
        result = r;
      }
    }
    return result;
  }

  private Result orderFailed(
      EventEnvelope event, TenantId tenant, @Nullable String tripId, @Nullable String orderId) {
    Result result = Result.IGNORED;
    for (Map<String, Object> item : list(event.data().get("items"))) {
      if ("UNKNOWN".equals(str(item.get("status")))) {
        Result r =
            opened(
                event,
                tenant,
                CaseKind.OUTCOME_UNKNOWN,
                Priority.CRITICAL,
                Queue.TRAVEL_OPS,
                "Supplier outcome unknown for "
                    + str(item.get("type"))
                    + " item on order "
                    + orderId,
                str(item.get("provider"))
                    + " may or may not hold a booking ("
                    + str(item.get("failureCode"))
                    + ")",
                tripId,
                orderId,
                null,
                null,
                str(item.get("componentId")),
                "unknown:" + orderId + ":" + str(item.get("itemId")),
                "Check with "
                    + str(item.get("provider"))
                    + " whether the booking exists; release it or record it, then resolve the order's exposure",
                "TRAVEL_ADMIN");
        if (r == Result.OPENED) {
          result = r;
        }
      }
    }
    return result;
  }

  private Result componentsReleased(
      EventEnvelope event,
      TenantId tenant,
      @Nullable String tripId,
      @Nullable String orderId,
      List<Map<String, Object>> components) {
    Result result = Result.IGNORED;
    for (Map<String, Object> c : components) {
      String componentId = str(c.get("componentId"));
      if ("CANCEL_FAILED".equals(str(c.get("status")))) {
        Result r =
            opened(
                event,
                tenant,
                CaseKind.CANCELLATION_INCOMPLETE,
                Priority.HIGH,
                Queue.TRAVEL_OPS,
                "Component "
                    + (c.get("summary") == null ? componentId : str(c.get("summary")))
                    + " of trip "
                    + tripId
                    + " could not be released",
                str(c.get("failureCode")),
                tripId,
                orderId,
                null,
                null,
                componentId,
                "component-cancel:" + tripId + ":" + componentId,
                "Release "
                    + str(c.get("externalRef"))
                    + " with "
                    + str(c.get("provider"))
                    + " by phone; record the resolution on the order's exposure");
        if (r == Result.OPENED) {
          result = r;
        }
      } else if ("CANCELLED".equals(str(c.get("status")))) {
        if (cases
            .settle(
                tenant,
                "component-cancel:" + tripId + ":" + componentId,
                "component released",
                PRODUCER,
                event.eventId())
            .isPresent()) {
          result = Result.SETTLED;
        }
      }
    }
    return result;
  }

  private Result opened(
      EventEnvelope event,
      TenantId tenant,
      CaseKind kind,
      Priority priority,
      Queue queue,
      String title,
      @Nullable String summary,
      @Nullable String tripId,
      @Nullable String orderId,
      @Nullable String disruptionId,
      @Nullable String exposureId,
      @Nullable String componentId,
      String dedupeKey,
      String nextAction) {
    return opened(
        event,
        tenant,
        kind,
        priority,
        queue,
        title,
        summary,
        tripId,
        orderId,
        disruptionId,
        exposureId,
        componentId,
        dedupeKey,
        nextAction,
        null);
  }

  private Result opened(
      EventEnvelope event,
      TenantId tenant,
      CaseKind kind,
      Priority priority,
      Queue queue,
      String title,
      @Nullable String summary,
      @Nullable String tripId,
      @Nullable String orderId,
      @Nullable String disruptionId,
      @Nullable String exposureId,
      @Nullable String componentId,
      String dedupeKey,
      String nextAction,
      @Nullable String role) {
    cases.open(
        new CaseService.OpenCase(
            tenant,
            kind,
            priority,
            queue,
            title,
            summary,
            tripId,
            orderId,
            str(event.data().get("travelerId")),
            disruptionId,
            exposureId,
            componentId,
            dedupeKey,
            nextAction,
            role,
            event.eventId(),
            event.eventType(),
            PRODUCER));
    return Result.OPENED;
  }

  private Result settled(TenantId tenant, String key, String resolution, EventEnvelope event) {
    return cases.settle(tenant, key, resolution, PRODUCER, event.eventId()).isPresent()
        ? Result.SETTLED
        : Result.INDEXED;
  }

  private Result settledPrefix(
      TenantId tenant, String prefix, @Nullable String id, String resolution, EventEnvelope event) {
    if (id == null) {
      return Result.IGNORED;
    }
    boolean any = false;
    for (String kind : List.of("recovery-approval:", "recovery-failed:")) {
      any |= cases.settle(tenant, kind + id, resolution, PRODUCER, event.eventId()).isPresent();
    }
    return any ? Result.SETTLED : Result.INDEXED;
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> list(@Nullable Object value) {
    if (value instanceof List<?> l) {
      return l.stream().filter(Map.class::isInstance).map(o -> (Map<String, Object>) o).toList();
    }
    return List.of();
  }

  private static @Nullable String str(@Nullable Object value) {
    if (value == null) {
      return null;
    }
    String s = String.valueOf(value);
    return s.isBlank() ? null : s;
  }

  private static String money(@Nullable Object value) {
    if (value instanceof Map<?, ?> m && m.get("currency") != null && m.get("amountMinor") != null) {
      long minor = ((Number) m.get("amountMinor")).longValue();
      return m.get("currency")
          + " "
          + (minor / 100)
          + "."
          + String.format("%02d", Math.abs(minor % 100));
    }
    return "an unknown amount";
  }
}
