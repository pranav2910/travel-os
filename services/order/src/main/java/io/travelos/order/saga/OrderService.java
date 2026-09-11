package io.travelos.order.saga;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.travelos.common.idempotency.IdempotencyKey;
import io.travelos.common.ids.IdPrefix;
import io.travelos.common.ids.Ids;
import io.travelos.common.money.Money;
import io.travelos.common.tenant.TenantId;
import io.travelos.contracts.common.v1.RequestContext;
import io.travelos.contracts.offer.v1.Bundle;
import io.travelos.contracts.offer.v1.Offer;
import io.travelos.contracts.offer.v1.OfferType;
import io.travelos.contracts.order.v1.CancelOrderCommand;
import io.travelos.contracts.order.v1.CreateOrderCommand;
import io.travelos.contracts.supplier.v1.CancelOrderRequest;
import io.travelos.contracts.supplier.v1.CancelOrderResponse;
import io.travelos.contracts.supplier.v1.CreateOrderRequest;
import io.travelos.contracts.supplier.v1.CreateOrderResponse;
import io.travelos.contracts.supplier.v1.PriceOfferRequest;
import io.travelos.contracts.supplier.v1.PriceOfferResponse;
import io.travelos.order.events.OrderEvents;
import io.travelos.order.store.OrderRecord;
import io.travelos.order.store.OrderRecord.Item;
import io.travelos.order.store.OrderRecord.ItemStatus;
import io.travelos.order.store.OrderRepository;
import io.travelos.order.store.OrderStatus;
import io.travelos.order.supplier.SupplierClient;
import io.travelos.spring.grpc.RequestContexts;
import io.travelos.spring.outbox.Outbox;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The booking saga. Each step commits its own state before the next supplier call, so a crash
 * anywhere leaves a resumable order: calling CreateOrder again with the same idempotency key
 * continues from the item states (PENDING items are attempted, CONFIRMED ones are skipped). A
 * component that fails after others were confirmed triggers compensation: every confirmed item is
 * cancelled at its supplier and the order becomes FAILED with compensated=true only when all of
 * them released. Nothing here relies on a SQL rollback across suppliers.
 */
@Service
public class OrderService {

  private static final Logger log = LoggerFactory.getLogger(OrderService.class);

  private final OrderRepository orders;
  private final SupplierClient suppliers;
  private final Outbox outbox;
  private final TransactionTemplate tx;
  private final Clock clock;

  public OrderService(
      OrderRepository orders,
      SupplierClient suppliers,
      Outbox outbox,
      TransactionTemplate tx,
      Clock clock) {
    this.orders = orders;
    this.suppliers = suppliers;
    this.outbox = outbox;
    this.tx = tx;
    this.clock = clock;
  }

  public OrderRecord create(CreateOrderCommand command) {
    RequestContexts.Validated ctx = RequestContexts.require(command.getCtx());
    String key = command.getCtx().getIdempotencyKey();
    if (key.isBlank()) {
      throw Status.INVALID_ARGUMENT
          .withDescription("ctx.idempotency_key is required")
          .asRuntimeException();
    }
    try {
      IdempotencyKey.parse(key);
    } catch (IllegalArgumentException e) {
      throw Status.INVALID_ARGUMENT
          .withDescription("ctx.idempotency_key: " + e.getMessage())
          .asRuntimeException();
    }
    if (command.getTripId().isBlank()
        || command.getTravelerId().isBlank()
        || !command.hasBundle()) {
      throw Status.INVALID_ARGUMENT
          .withDescription("trip_id, traveler_id and bundle are required")
          .asRuntimeException();
    }
    if (command.getPassengersCount() == 0) {
      throw Status.INVALID_ARGUMENT
          .withDescription("at least one passenger is required")
          .asRuntimeException();
    }
    Bundle bundle = command.getBundle();
    for (Offer offer : bundle.getOffersList()) {
      if (offer.getType() != OfferType.AIR) {
        throw Status.FAILED_PRECONDITION
            .withDescription("UNSUPPORTED_ITEM: only AIR items can be booked in this release")
            .asRuntimeException();
      }
    }

    // Idempotent replay, or resume of an interrupted saga.
    Optional<OrderRecord> existing = orders.findByIdempotencyKey(ctx.tenant(), key);
    OrderRecord order;
    if (existing.isPresent()) {
      order = existing.get();
      if (!order.bundleId().equals(bundle.getBundleId())) {
        throw Status.FAILED_PRECONDITION
            .withDescription(
                "IDEMPOTENCY_KEY_REUSED: key already used for bundle " + order.bundleId())
            .asRuntimeException();
      }
      if (order.status() != OrderStatus.CREATING) {
        return order;
      }
      log.info("resuming saga for {} ({} items)", order.orderId(), order.items().size());
    } else {
      order = tx.execute(status -> createOrder(ctx, command, key));
    }
    return runSaga(ctx, command, order);
  }

  private OrderRecord createOrder(
      RequestContexts.Validated ctx, CreateOrderCommand command, String key) {
    Instant now = clock.instant();
    Bundle bundle = command.getBundle();
    Money total = bundle.hasTotal() ? money(bundle.getTotal()) : null;
    List<Item> items = new ArrayList<>();
    int position = 0;
    for (Offer offer : bundle.getOffersList()) {
      Money itemTotal = money(offer.getTotal());
      total = total == null ? itemTotal : (bundle.hasTotal() ? total : total.plus(itemTotal));
      items.add(
          new Item(
              Ids.newId(IdPrefix.ORDER_ITEM),
              position++,
              offer.getType().name(),
              offer.getProvider(),
              offer.getProviderOfferId(),
              toJson(offer),
              ItemStatus.PENDING,
              null,
              null,
              itemTotal,
              null,
              now));
    }
    if (total == null) {
      throw Status.INVALID_ARGUMENT.withDescription("bundle has no offers").asRuntimeException();
    }
    OrderRecord order =
        new OrderRecord(
            Ids.newId(IdPrefix.ORDER),
            ctx.tenant(),
            command.getTripId(),
            command.getTravelerId(),
            bundle.getBundleId(),
            items.getFirst().provider(),
            null,
            OrderStatus.CREATING,
            total,
            key,
            blankToNull(command.getPolicyDecisionId()),
            blankToNull(command.getOptimizationRunId()),
            blankToNull(command.getApprovalId()),
            null,
            null,
            false,
            ctx.principal(),
            0,
            now,
            now,
            items);
    orders.insert(order);
    outbox.append(OrderEvents.created(order, command.getCtx().getCausationId(), clock));
    return order;
  }

  private OrderRecord runSaga(
      RequestContexts.Validated ctx, CreateOrderCommand command, OrderRecord order) {
    for (Item item : order.items()) {
      if (item.status() == ItemStatus.CONFIRMED) {
        continue;
      }
      try {
        bookItem(ctx, command, order, item);
      } catch (StatusRuntimeException e) {
        String code = failureCode(e);
        log.warn("order {} item {} failed: {}", order.orderId(), item.itemId(), e.getStatus());
        tx.executeWithoutResult(
            s ->
                orders.updateItem(
                    item.itemId(), ItemStatus.FAILED, null, null, code, clock.instant()));
        return fail(
            ctx,
            order,
            code,
            e.getStatus().getDescription() == null ? code : e.getStatus().getDescription());
      }
    }
    OrderRecord confirmed = orders.find(ctx.tenant(), order.orderId()).orElseThrow();
    String external = confirmed.items().getFirst().externalRef();
    return tx.execute(
        s -> {
          OrderRecord current = orders.find(ctx.tenant(), order.orderId()).orElseThrow();
          if (current.status() == OrderStatus.CREATING) {
            orders.transition(
                current,
                OrderStatus.CONFIRMED,
                "all items confirmed",
                external,
                null,
                null,
                false,
                clock.instant());
            OrderRecord done = orders.find(ctx.tenant(), order.orderId()).orElseThrow();
            outbox.append(OrderEvents.confirmed(done, command.getCtx().getCausationId(), clock));
            return done;
          }
          return current;
        });
  }

  private void bookItem(
      RequestContexts.Validated ctx, CreateOrderCommand command, OrderRecord order, Item item) {
    RequestContext supplierCtx =
        command.getCtx().toBuilder()
            .setIdempotencyKey(order.orderId() + ":" + item.itemId())
            .build();
    // Re-price: offers expire and prices move. A higher price is not silently accepted.
    PriceOfferResponse priced =
        suppliers.price(
            PriceOfferRequest.newBuilder()
                .setCtx(supplierCtx)
                .setProvider(item.provider())
                .setProviderOfferId(item.providerOfferId())
                .build());
    Money repriced = money(priced.getOffer().getTotal());
    if (priced.getPriceChanged() && repriced.isGreaterThan(item.total())) {
      throw Status.FAILED_PRECONDITION
          .withDescription(
              "PRICE_CHANGED: " + item.total() + " is now " + repriced + "; re-evaluate policy")
          .asRuntimeException();
    }
    CreateOrderResponse created =
        suppliers.createOrder(
            CreateOrderRequest.newBuilder()
                .setCtx(supplierCtx)
                .setProvider(item.provider())
                .setProviderOfferId(item.providerOfferId())
                .addAllPassengers(command.getPassengersList())
                .setPaymentToken(command.getPaymentToken())
                .build());
    tx.executeWithoutResult(
        s ->
            orders.updateItem(
                item.itemId(),
                ItemStatus.CONFIRMED,
                created.getExternalOrderId(),
                created.getRecordLocator(),
                null,
                clock.instant()));
  }

  /**
   * Compensate whatever was confirmed, then mark the order FAILED with an honest compensated flag.
   */
  private OrderRecord fail(
      RequestContexts.Validated ctx, OrderRecord order, String code, String message) {
    OrderRecord current = orders.find(ctx.tenant(), order.orderId()).orElseThrow();
    boolean allReleased = true;
    for (Item item : current.items()) {
      if (item.status() != ItemStatus.CONFIRMED) {
        continue;
      }
      try {
        suppliers.cancelOrder(
            CancelOrderRequest.newBuilder()
                .setCtx(
                    RequestContext.newBuilder()
                        .setTenantId(ctx.tenant().value())
                        .setCorrelationId(current.tripId())
                        .setPrincipal(
                            io.travelos.contracts.common.v1.Principal.newBuilder()
                                .setKind(io.travelos.contracts.common.v1.Principal.Kind.SERVICE)
                                .setId("service/order")))
                .setProvider(item.provider())
                .setExternalOrderId(item.externalRef())
                .build());
        tx.executeWithoutResult(
            s ->
                orders.updateItem(
                    item.itemId(), ItemStatus.CANCELLED, null, null, null, clock.instant()));
      } catch (StatusRuntimeException e) {
        log.error(
            "compensation failed for order {} item {} ({}): needs human attention",
            current.orderId(),
            item.itemId(),
            item.externalRef(),
            e);
        allReleased = false;
      }
    }
    boolean compensated = allReleased;
    return tx.execute(
        s -> {
          OrderRecord fresh = orders.find(ctx.tenant(), order.orderId()).orElseThrow();
          OrderStatus to = compensated ? OrderStatus.FAILED : OrderStatus.PARTIALLY_FAILED;
          orders.transition(fresh, to, code, null, code, message, compensated, clock.instant());
          OrderRecord failed = orders.find(ctx.tenant(), order.orderId()).orElseThrow();
          outbox.append(OrderEvents.failed(failed, code, message, compensated, null, clock));
          return failed;
        });
  }

  public OrderRecord get(TenantId tenant, String orderId) {
    return orders
        .find(tenant, orderId)
        .orElseThrow(
            () ->
                Status.NOT_FOUND
                    .withDescription("order " + orderId + " not found")
                    .asRuntimeException());
  }

  public List<OrderRecord> byTrip(TenantId tenant, String tripId) {
    return orders.byTrip(tenant, tripId);
  }

  /** Idempotent by state: cancelling a cancelled order returns it. */
  public OrderRecord cancel(CancelOrderCommand command) {
    RequestContexts.Validated ctx = RequestContexts.require(command.getCtx());
    OrderRecord order = get(ctx.tenant(), command.getOrderId());
    if (order.status() == OrderStatus.CANCELLED) {
      return order;
    }
    if (!order.status().canTransitionTo(OrderStatus.CANCELLED)) {
      throw Status.FAILED_PRECONDITION
          .withDescription("ORDER_NOT_CANCELLABLE: status " + order.status())
          .asRuntimeException();
    }
    Money refund = null;
    for (Item item : order.items()) {
      if (item.status() != ItemStatus.CONFIRMED) {
        continue;
      }
      CancelOrderResponse response =
          suppliers.cancelOrder(
              CancelOrderRequest.newBuilder()
                  .setCtx(command.getCtx())
                  .setProvider(item.provider())
                  .setExternalOrderId(item.externalRef())
                  .build());
      Money itemRefund = money(response.getRefund());
      refund = refund == null ? itemRefund : refund.plus(itemRefund);
      tx.executeWithoutResult(
          s ->
              orders.updateItem(
                  item.itemId(), ItemStatus.CANCELLED, null, null, null, clock.instant()));
    }
    Money finalRefund = refund;
    String reason = command.getReason().isBlank() ? "cancelled" : command.getReason();
    return tx.execute(
        s -> {
          OrderRecord fresh = orders.find(ctx.tenant(), order.orderId()).orElseThrow();
          orders.transition(
              fresh, OrderStatus.CANCELLED, reason, null, null, null, false, clock.instant());
          OrderRecord cancelled = orders.find(ctx.tenant(), order.orderId()).orElseThrow();
          outbox.append(
              OrderEvents.cancelled(
                  cancelled,
                  finalRefund,
                  reason,
                  ctx.principal(),
                  command.getCtx().getCausationId(),
                  clock));
          return cancelled;
        });
  }

  static String failureCode(StatusRuntimeException e) {
    String description = e.getStatus().getDescription();
    if (description != null && description.contains(":")) {
      String candidate = description.substring(0, description.indexOf(':')).trim();
      if (candidate.matches("[A-Z][A-Z0-9_]*")) {
        return candidate;
      }
    }
    return "SUPPLIER_" + e.getStatus().getCode().name();
  }

  static Money money(io.travelos.contracts.common.v1.Money m) {
    return Money.of(m.getCurrency(), m.getAmountMinor());
  }

  static String toJson(Offer offer) {
    try {
      return JsonFormat.printer().omittingInsignificantWhitespace().print(offer);
    } catch (InvalidProtocolBufferException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s;
  }
}
