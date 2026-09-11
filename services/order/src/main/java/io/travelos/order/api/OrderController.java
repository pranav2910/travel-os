package io.travelos.order.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.travelos.common.money.Money;
import io.travelos.order.saga.OrderService;
import io.travelos.order.store.OrderRecord;
import io.travelos.spring.web.auth.RequestPrincipal;
import io.travelos.spring.web.error.ApiException;
import java.time.Instant;
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
      long version,
      Instant createdAt,
      Instant updatedAt) {

    static OrderResponse from(OrderRecord o) {
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
          o.version(),
          o.createdAt(),
          o.updatedAt());
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
      String status,
      @Nullable String externalRef,
      @Nullable String recordLocator,
      MoneyView total,
      @Nullable String failureCode) {
    static ItemView from(OrderRecord.Item i) {
      return new ItemView(
          i.itemId(),
          i.offerType(),
          i.provider(),
          i.status().name(),
          i.externalRef(),
          i.recordLocator(),
          MoneyView.of(i.total()),
          i.failureCode());
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
    return OrderResponse.from(order);
  }

  @GetMapping
  public List<OrderResponse> byTrip(
      @AuthenticationPrincipal RequestPrincipal me, @RequestParam String tripId) {
    return orders.byTrip(me.tenant(), tripId).stream()
        .filter(o -> canRead(me, o))
        .map(OrderResponse::from)
        .toList();
  }

  private static boolean canRead(RequestPrincipal me, OrderRecord order) {
    return order.travelerId().equals(me.employeeId()) || me.hasAnyRole(TENANT_WIDE);
  }
}
