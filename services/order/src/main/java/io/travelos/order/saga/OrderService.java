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
import io.travelos.contracts.order.v1.ChangeOrderCommand;
import io.travelos.contracts.order.v1.CreateOrderCommand;
import io.travelos.contracts.supplier.v1.CancelOrderRequest;
import io.travelos.contracts.supplier.v1.CancelOrderResponse;
import io.travelos.contracts.supplier.v1.ChangeOrderRequest;
import io.travelos.contracts.supplier.v1.ChangeOrderResponse;
import io.travelos.contracts.supplier.v1.CreateOrderRequest;
import io.travelos.contracts.supplier.v1.CreateOrderResponse;
import io.travelos.contracts.supplier.v1.PriceOfferRequest;
import io.travelos.contracts.supplier.v1.PriceOfferResponse;
import io.travelos.order.events.OrderEvents;
import io.travelos.order.store.OrderChangeRecord;
import io.travelos.order.store.OrderChangeRepository;
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
  private final OrderChangeRepository changes;
  private final SupplierClient suppliers;
  private final Outbox outbox;
  private final TransactionTemplate tx;
  private final Clock clock;

  public OrderService(
      OrderRepository orders,
      OrderChangeRepository changes,
      SupplierClient suppliers,
      Outbox outbox,
      TransactionTemplate tx,
      Clock clock) {
    this.orders = orders;
    this.changes = changes;
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

  /** Impacted-trip detection for the Disruption service. 404 when the reference is not ours. */
  public OrderRecord findByExternalRef(TenantId tenant, String supplier, String externalOrderId) {
    return orders
        .findByExternalRef(tenant, supplier, externalOrderId)
        .orElseThrow(
            () ->
                Status.NOT_FOUND
                    .withDescription("no order for " + supplier + " reference " + externalOrderId)
                    .asRuntimeException());
  }

  public List<OrderChangeRecord> changesOf(TenantId tenant, String orderId) {
    return changes.byOrder(tenant, orderId);
  }

  /**
   * The change saga (disruption recovery). Idempotent by ctx.idempotency_key: a retry after a crash
   * finds the PENDING change and resumes it — the supplier is called again with the SAME
   * supplier-side key, so it reissues once whatever we saw of its first answer. An APPLIED or
   * FAILED change is final for that key: the order is returned as it is.
   */
  public OrderRecord change(ChangeOrderCommand command) {
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
    if (command.getOrderId().isBlank() || !command.hasReplacement()) {
      throw Status.INVALID_ARGUMENT
          .withDescription("order_id and replacement are required")
          .asRuntimeException();
    }
    Bundle replacement = command.getReplacement();
    if (replacement.getOffersCount() != 1 || replacement.getOffers(0).getType() != OfferType.AIR) {
      throw Status.FAILED_PRECONDITION
          .withDescription("UNSUPPORTED_CHANGE: a change replaces exactly one AIR item")
          .asRuntimeException();
    }
    if (command.getPassengersCount() == 0) {
      throw Status.INVALID_ARGUMENT
          .withDescription("at least one passenger is required")
          .asRuntimeException();
    }
    OrderRecord order = get(ctx.tenant(), command.getOrderId());

    Optional<OrderChangeRecord> existing = changes.findByIdempotencyKey(ctx.tenant(), key);
    OrderChangeRecord change;
    if (existing.isPresent()) {
      change = existing.get();
      if (!change.orderId().equals(order.orderId())) {
        throw Status.FAILED_PRECONDITION
            .withDescription(
                "IDEMPOTENCY_KEY_REUSED: key already used for order " + change.orderId())
            .asRuntimeException();
      }
      if (change.status() != OrderChangeRecord.Status.PENDING) {
        return order;
      }
      log.info("resuming change {} for order {}", change.changeId(), order.orderId());
    } else {
      if (order.status() != OrderStatus.CONFIRMED && order.status() != OrderStatus.CHANGED) {
        throw Status.FAILED_PRECONDITION
            .withDescription("ORDER_NOT_CHANGEABLE: status " + order.status())
            .asRuntimeException();
      }
      String currentOffer =
          order.items().stream()
              .filter(i -> i.status() == ItemStatus.CONFIRMED)
              .map(Item::providerOfferId)
              .reduce((a, b) -> b)
              .orElse("");
      if (order.bundleId().equals(replacement.getBundleId())
          || currentOffer.equals(replacement.getOffers(0).getProviderOfferId())) {
        throw Status.FAILED_PRECONDITION
            .withDescription("SAME_ITINERARY: the replacement is the current itinerary")
            .asRuntimeException();
      }
      OrderRecord current = order;
      change = tx.execute(status -> requestChange(ctx, command, current, key));
    }
    return applyChange(ctx, command, change);
  }

  private OrderChangeRecord requestChange(
      RequestContexts.Validated ctx, ChangeOrderCommand command, OrderRecord order, String key) {
    Instant now = clock.instant();
    Offer offer = command.getReplacement().getOffers(0);
    OrderChangeRecord change =
        new OrderChangeRecord(
            Ids.newId(IdPrefix.ORDER_CHANGE),
            order.orderId(),
            ctx.tenant(),
            blankToNull(command.getDisruptionId()),
            key,
            OrderChangeRecord.Status.PENDING,
            order.status(),
            order.bundleId(),
            command.getReplacement().getBundleId(),
            toJson(offer),
            order.total().currency(),
            null,
            blankToNull(command.getPolicyDecisionId()),
            blankToNull(command.getOptimizationRunId()),
            blankToNull(command.getApprovalId()),
            null,
            null,
            null,
            null,
            ctx.principal(),
            now,
            now);
    if (!orders.transition(
        order,
        OrderStatus.CHANGE_PENDING,
        "change requested" + (change.disruptionId() == null ? "" : " for " + change.disruptionId()),
        null,
        null,
        null,
        order.compensated(),
        now)) {
      throw Status.ABORTED
          .withDescription("order changed concurrently; retry")
          .asRuntimeException();
    }
    changes.insert(change);
    OrderRecord pending = orders.find(ctx.tenant(), order.orderId()).orElseThrow();
    outbox.append(
        OrderEvents.changeRequested(pending, change, command.getCtx().getCausationId(), clock));
    return change;
  }

  private OrderRecord applyChange(
      RequestContexts.Validated ctx, ChangeOrderCommand command, OrderChangeRecord change) {
    OrderRecord order = orders.find(ctx.tenant(), change.orderId()).orElseThrow();
    Offer offer = command.getReplacement().getOffers(0);
    Item currentItem =
        order.items().stream()
            .filter(i -> i.status() == ItemStatus.CONFIRMED)
            .reduce((a, b) -> b)
            .orElse(null);
    if (currentItem == null || currentItem.externalRef() == null) {
      return failChange(
          ctx, order, change, "NO_CONFIRMED_ITEM", "the order has no confirmed item to change");
    }
    RequestContext supplierCtx =
        command.getCtx().toBuilder()
            .setIdempotencyKey(order.orderId() + ":" + change.changeId())
            .build();
    ChangeOrderResponse changed;
    try {
      PriceOfferResponse priced =
          suppliers.price(
              PriceOfferRequest.newBuilder()
                  .setCtx(supplierCtx)
                  .setProvider(offer.getProvider())
                  .setProviderOfferId(offer.getProviderOfferId())
                  .build());
      Money quoted = money(offer.getTotal());
      Money repriced = money(priced.getOffer().getTotal());
      if (priced.getPriceChanged() && repriced.isGreaterThan(quoted)) {
        throw Status.FAILED_PRECONDITION
            .withDescription(
                "PRICE_CHANGED: " + quoted + " is now " + repriced + "; re-evaluate policy")
            .asRuntimeException();
      }
      changed =
          suppliers.changeOrder(
              ChangeOrderRequest.newBuilder()
                  .setCtx(supplierCtx)
                  .setProvider(currentItem.provider())
                  .setExternalOrderId(currentItem.externalRef())
                  .setNewProviderOfferId(offer.getProviderOfferId())
                  .setPaymentToken(command.getPaymentToken())
                  .build());
    } catch (StatusRuntimeException e) {
      if (isTransient(e.getStatus())) {
        throw e; // the caller (a Temporal activity) retries; the PENDING change resumes
      }
      String code = failureCode(e);
      log.warn("order {} change {} failed: {}", order.orderId(), change.changeId(), e.getStatus());
      return failChange(
          ctx,
          order,
          change,
          code,
          e.getStatus().getDescription() == null ? code : e.getStatus().getDescription());
    }
    Money incremental = money(changed.getIncrementalCost());
    Money newTotal = order.total().plus(incremental);
    Instant now = clock.instant();
    Item replacementItem =
        new Item(
            Ids.newId(IdPrefix.ORDER_ITEM),
            order.items().stream().mapToInt(Item::position).max().orElse(-1) + 1,
            offer.getType().name(),
            offer.getProvider(),
            offer.getProviderOfferId(),
            toJson(offer),
            ItemStatus.CONFIRMED,
            changed.getExternalOrderId(),
            changed.getRecordLocator(),
            money(offer.getTotal()),
            null,
            now);
    return tx.execute(
        s -> {
          OrderRecord fresh = orders.find(ctx.tenant(), order.orderId()).orElseThrow();
          orders.updateItem(currentItem.itemId(), ItemStatus.CHANGED, null, null, null, now);
          orders.insertItem(order.orderId(), ctx.tenant(), replacementItem);
          if (!orders.replaceItinerary(
              fresh,
              OrderStatus.CHANGED,
              change.replacementBundleId(),
              newTotal,
              changed.getExternalOrderId(),
              "reissued at " + currentItem.provider() + " (" + change.changeId() + ")",
              now)) {
            throw Status.ABORTED
                .withDescription("order changed concurrently; retry")
                .asRuntimeException();
          }
          changes.complete(
              change.changeId(),
              OrderChangeRecord.Status.APPLIED,
              incremental.amountMinor(),
              changed.getExternalOrderId(),
              changed.getRecordLocator(),
              null,
              null,
              now);
          OrderRecord done = orders.find(ctx.tenant(), order.orderId()).orElseThrow();
          OrderChangeRecord applied = changes.find(ctx.tenant(), change.changeId()).orElseThrow();
          outbox.append(
              OrderEvents.changed(done, applied, command.getCtx().getCausationId(), clock));
          return done;
        });
  }

  /** A final supplier answer: the change is over, the order goes back to what it was. */
  private OrderRecord failChange(
      RequestContexts.Validated ctx,
      OrderRecord order,
      OrderChangeRecord change,
      String code,
      String message) {
    Instant now = clock.instant();
    return tx.execute(
        s -> {
          changes.complete(
              change.changeId(),
              OrderChangeRecord.Status.FAILED,
              null,
              null,
              null,
              code,
              message.length() > 1000 ? message.substring(0, 1000) : message,
              now);
          OrderRecord fresh = orders.find(ctx.tenant(), order.orderId()).orElseThrow();
          if (fresh.status() == OrderStatus.CHANGE_PENDING) {
            orders.transition(
                fresh,
                change.previousStatus(),
                "change " + change.changeId() + " failed: " + code,
                null,
                null,
                null,
                fresh.compensated(),
                now);
          }
          return orders.find(ctx.tenant(), order.orderId()).orElseThrow();
        });
  }

  /** Statuses the supplier client already retried and that may still clear up later. */
  static boolean isTransient(Status status) {
    return switch (status.getCode()) {
      case UNAVAILABLE, DEADLINE_EXCEEDED, RESOURCE_EXHAUSTED, ABORTED, UNKNOWN, INTERNAL -> true;
      default -> false;
    };
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
