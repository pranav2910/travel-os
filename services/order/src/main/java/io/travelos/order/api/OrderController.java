package io.travelos.order.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import io.travelos.common.money.Money;
import io.travelos.contracts.offer.v1.FlightSegment;
import io.travelos.contracts.offer.v1.Offer;
import io.travelos.order.saga.OrderService;
import io.travelos.order.store.OrderChangeRecord;
import io.travelos.order.store.OrderRecord;
import io.travelos.spring.web.auth.RequestPrincipal;
import io.travelos.spring.web.error.ApiException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read side for people. Travelers see their own orders; tenant-wide roles see the tenant's. */
@RestController
@RequestMapping(path = "/api/v1/orders", produces = "application/json")
public class OrderController {

  private static final String[] TENANT_WIDE = {"MANAGER", "TRAVEL_ADMIN", "FINANCE"};

  private final OrderService orders;

  public OrderController(OrderService orders) {
    this.orders = orders;
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record OrderResponse(
      String orderId,
      String tripId,
      String travelerId,
      String bundleId,
      String supplier,
      @Nullable String externalOrderId,
      String status,
      MoneyView total,
      @Nullable String policyDecisionId,
      @Nullable String optimizationRunId,
      @Nullable String approvalId,
      @Nullable String failureCode,
      @Nullable String failureMessage,
      boolean compensated,
      List<ItemView> items,
      List<ChangeView> changes,
      long version,
      Instant createdAt,
      Instant updatedAt) {

    static OrderResponse from(OrderRecord o, List<OrderChangeRecord> changes) {
      return new OrderResponse(
          o.orderId(),
          o.tripId(),
          o.travelerId(),
          o.bundleId(),
          o.supplier(),
          o.externalOrderId(),
          o.status().name(),
          MoneyView.of(o.total()),
          o.policyDecisionId(),
          o.optimizationRunId(),
          o.approvalId(),
          o.failureCode(),
          o.failureMessage(),
          o.compensated(),
          o.items().stream().map(ItemView::from).toList(),
          changes.stream().map(ChangeView::from).toList(),
          o.version(),
          o.createdAt(),
          o.updatedAt());
    }
  }

  /**
   * One logical change of the itinerary (Slice 2): what replaced what, at what cost, or why not.
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record ChangeView(
      String changeId,
      @Nullable String disruptionId,
      String status,
      String idempotencyKey,
      String previousBundleId,
      String replacementBundleId,
      @Nullable MoneyView incrementalCost,
      @Nullable String policyDecisionId,
      @Nullable String optimizationRunId,
      @Nullable String approvalId,
      @Nullable String externalOrderId,
      @Nullable String recordLocator,
      @Nullable String failureCode,
      String requestedBy,
      Instant createdAt,
      Instant updatedAt) {
    static ChangeView from(OrderChangeRecord c) {
      return new ChangeView(
          c.changeId(),
          c.disruptionId(),
          c.status().name(),
          c.idempotencyKey(),
          c.previousBundleId(),
          c.replacementBundleId(),
          c.incrementalMinor() == null
              ? null
              : MoneyView.of(Money.of(c.currency(), c.incrementalMinor())),
          c.policyDecisionId(),
          c.optimizationRunId(),
          c.approvalId(),
          c.externalOrderId(),
          c.recordLocator(),
          c.failureCode(),
          c.requestedBy().id(),
          c.createdAt(),
          c.updatedAt());
    }
  }

  public record MoneyView(String currency, long amountMinor, String display) {
    static MoneyView of(Money m) {
      return new MoneyView(m.currency(), m.amountMinor(), m.toString());
    }
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record ItemView(
      String itemId,
      String type,
      String provider,
      String providerOfferId,
      String status,
      @Nullable String externalRef,
      @Nullable String recordLocator,
      MoneyView total,
      @Nullable String failureCode,
      List<FlightView> flights) {
    static ItemView from(OrderRecord.Item i) {
      return new ItemView(
          i.itemId(),
          i.offerType(),
          i.provider(),
          i.providerOfferId(),
          i.status().name(),
          i.externalRef(),
          i.recordLocator(),
          MoneyView.of(i.total()),
          i.failureCode(),
          FlightView.of(i.offerJson()));
    }
  }

  /** The flights an item books, in order: outbound legs then inbound legs. */
  public record FlightView(
      String direction,
      String carrier,
      String flightNumber,
      String origin,
      String destination,
      @Nullable Instant departure,
      @Nullable Instant arrival,
      String cabin) {
    static List<FlightView> of(String offerJson) {
      Offer.Builder b = Offer.newBuilder();
      try {
        JsonFormat.parser().ignoringUnknownFields().merge(offerJson, b);
      } catch (InvalidProtocolBufferException e) {
        return List.of();
      }
      if (!b.hasAir()) {
        return List.of();
      }
      List<FlightView> out = new ArrayList<>();
      for (FlightSegment seg : b.getAir().getOutbound().getSegmentsList()) {
        out.add(of("OUTBOUND", seg));
      }
      for (FlightSegment seg : b.getAir().getInbound().getSegmentsList()) {
        out.add(of("INBOUND", seg));
      }
      return out;
    }

    private static FlightView of(String direction, FlightSegment seg) {
      return new FlightView(
          direction,
          seg.getCarrier(),
          seg.getFlightNumber(),
          seg.getOrigin(),
          seg.getDestination(),
          seg.hasDeparture() ? Instant.ofEpochSecond(seg.getDeparture().getSeconds()) : null,
          seg.hasArrival() ? Instant.ofEpochSecond(seg.getArrival().getSeconds()) : null,
          seg.getCabin().name());
    }
  }

  @GetMapping("/{orderId}")
  public OrderResponse get(
      @AuthenticationPrincipal RequestPrincipal me, @PathVariable String orderId) {
    OrderRecord order;
    try {
      order = orders.get(me.tenant(), orderId);
    } catch (io.grpc.StatusRuntimeException e) {
      throw new ApiException.NotFound("order", orderId);
    }
    if (!canRead(me, order)) {
      throw new ApiException.NotFound("order", orderId);
    }
    return OrderResponse.from(order, orders.changesOf(me.tenant(), order.orderId()));
  }

  @GetMapping
  public List<OrderResponse> byTrip(
      @AuthenticationPrincipal RequestPrincipal me, @RequestParam String tripId) {
    return orders.byTrip(me.tenant(), tripId).stream()
        .filter(o -> canRead(me, o))
        .map(o -> OrderResponse.from(o, orders.changesOf(me.tenant(), o.orderId())))
        .toList();
  }

  private static boolean canRead(RequestPrincipal me, OrderRecord order) {
    return order.travelerId().equals(me.employeeId()) || me.hasAnyRole(TENANT_WIDE);
  }
}
