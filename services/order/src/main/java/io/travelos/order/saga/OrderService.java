package io.travelos.order.saga;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.travelos.common.idempotency.IdempotencyKey;
import io.travelos.common.identity.Principal;
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
import io.travelos.contracts.supplier.v1.BookingStatus;
import io.travelos.contracts.supplier.v1.CancelOrderRequest;
import io.travelos.contracts.supplier.v1.CancelOrderResponse;
import io.travelos.contracts.supplier.v1.ChangeOrderRequest;
import io.travelos.contracts.supplier.v1.ChangeOrderResponse;
import io.travelos.contracts.supplier.v1.CreateOrderRequest;
import io.travelos.contracts.supplier.v1.CreateOrderResponse;
import io.travelos.contracts.supplier.v1.GetBookingStatusRequest;
import io.travelos.contracts.supplier.v1.PriceOfferRequest;
import io.travelos.contracts.supplier.v1.PriceOfferResponse;
import io.travelos.contracts.supplier.v1.QuoteOfferRequest;
import io.travelos.contracts.supplier.v1.QuoteOfferResponse;
import io.travelos.contracts.supplier.v1.SupplierOrderStatus;
import io.travelos.order.events.OrderEvents;
import io.travelos.order.metrics.OrderMetrics;
import io.travelos.order.store.ExposureRecord;
import io.travelos.order.store.ExposureRepository;
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
import org.jspecify.annotations.Nullable;
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
  private final ExposureRepository exposures;
  private final SupplierClient suppliers;
  private final Outbox outbox;
  private final TransactionTemplate tx;
  private final Clock clock;
  private final OrderMetrics metrics;
  private final io.travelos.order.finance.FinanceService finance;

  public OrderService(
      OrderRepository orders,
      OrderChangeRepository changes,
      ExposureRepository exposures,
      SupplierClient suppliers,
      Outbox outbox,
      TransactionTemplate tx,
      Clock clock,
      OrderMetrics metrics,
      io.travelos.order.finance.FinanceService finance) {
    this.orders = orders;
    this.changes = changes;
    this.exposures = exposures;
    this.suppliers = suppliers;
    this.outbox = outbox;
    this.tx = tx;
    this.clock = clock;
    this.metrics = metrics;
    this.finance = finance;
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
    boolean tagged = false;
    for (Offer offer : bundle.getOffersList()) {
      if (offer.getType() != OfferType.AIR
          && offer.getType() != OfferType.HOTEL
          && offer.getType() != OfferType.GROUND) {
        throw Status.FAILED_PRECONDITION
            .withDescription("UNSUPPORTED_ITEM: offer " + offer.getOfferId() + " has no type")
            .asRuntimeException();
      }
      tagged |= !offer.getComponentId().isBlank();
    }
    if (tagged && bundle.getOffersList().stream().anyMatch(o -> o.getComponentId().isBlank())) {
      throw Status.INVALID_ARGUMENT
          .withDescription("every offer of an itinerary bundle needs a component_id")
          .asRuntimeException();
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
    // Booking order is dependency order: legs first, then the stays that follow them, then the
    // transfers that meet them. Compensation walks the same list backwards.
    for (Offer offer : inBookingOrder(bundle.getOffersList())) {
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
              now,
              blankToNull(offer.getComponentId())));
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
    // Phase 5 (ADR-0017): the order total is authorized on the instrument before any supplier
    // hears of the order; a decline fails it here, with nothing to compensate.
    io.travelos.order.finance.FinanceRecords.Payment payment =
        finance.authorize(order, command.getPaymentToken(), ctx.principal());
    if (payment.status() == io.travelos.order.finance.FinanceRecords.PaymentStatus.DECLINED) {
      return fail(
          ctx,
          order,
          "PAYMENT_DECLINED",
          payment.failureCode()
              + ": "
              + (payment.failureMessage() == null ? "declined" : payment.failureMessage()));
    }
    for (Item item : order.items()) {
      if (item.status() == ItemStatus.CONFIRMED) {
        continue;
      }
      try {
        bookItem(ctx, command, order, item);
      } catch (StatusRuntimeException e) {
        String code = failureCode(e);
        if (e.getStatus().getCode() == Status.Code.ABORTED && "OUTCOME_UNKNOWN".equals(code)) {
          // Phase 4: the supplier may have booked it. Nobody retries; the money is exposed until a
          // person reconciles (the gateway's ledger keeps the attempt).
          log.error(
              "order {} item {}: outcome unknown at {}; exposure recorded for a person",
              order.orderId(),
              item.itemId(),
              item.provider());
          metrics.booked(item.offerType(), "UNKNOWN");
          ExposureRecord exposure =
              new ExposureRecord(
                  Ids.newId(IdPrefix.EXPOSURE),
                  order.orderId(),
                  item.itemId(),
                  item.componentId(),
                  item.provider(),
                  "",
                  item.total(),
                  "OUTCOME_UNKNOWN",
                  e.getStatus().getDescription() == null ? code : e.getStatus().getDescription(),
                  ExposureRecord.Status.OPEN,
                  null,
                  null,
                  null,
                  clock.instant(),
                  null);
          tx.executeWithoutResult(
              s -> {
                orders.updateItem(
                    item.itemId(), ItemStatus.UNKNOWN, null, null, code, clock.instant());
                exposures.insert(ctx.tenant(), exposure);
              });
          return fail(
              ctx,
              order,
              code,
              e.getStatus().getDescription() == null ? code : e.getStatus().getDescription());
        }
        log.warn("order {} item {} failed: {}", order.orderId(), item.itemId(), e.getStatus());
        metrics.booked(item.offerType(), "FAILED");
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
    // Everything is confirmed: capture what the suppliers charged (never more than authorized).
    Money charged = Money.of(confirmed.total().currency(), 0);
    for (Item item : confirmed.items()) {
      if (item.status() == ItemStatus.CONFIRMED
          && item.total().currency().equals(charged.currency())) {
        charged = charged.plus(item.total());
      }
    }
    finance.capture(confirmed, charged);
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
    // Re-validate: offers expire and prices move. A higher price is not silently accepted; an
    // expired quote at the same price is re-quoted and booked as re-quoted.
    String providerOfferId = revalidate(supplierCtx, item);
    CreateOrderRequest request =
        CreateOrderRequest.newBuilder()
            .setCtx(supplierCtx)
            .setProvider(item.provider())
            .setProviderOfferId(providerOfferId)
            .addAllPassengers(command.getPassengersList())
            .setPaymentToken(command.getPaymentToken())
            .build();
    CreateOrderResponse created;
    boolean reconciledByLookup = false;
    try {
      created = suppliers.createOrder(request);
    } catch (StatusRuntimeException e) {
      if (!SupplierClient.isRetryable(e.getStatus())) {
        throw e;
      }
      // The answer was lost after the client's own retries. Before treating that as a failure,
      // ask the supplier what it did with our key: a booking it made is adopted, never made twice.
      CreateOrderResponse reconciled = reconcile(supplierCtx, item);
      if (reconciled == null) {
        throw e;
      }
      log.warn(
          "order {} item {}: supplier answer lost, booking {} reconciled by status lookup",
          order.orderId(),
          item.itemId(),
          reconciled.getExternalOrderId());
      created = reconciled;
      reconciledByLookup = true;
    }
    CreateOrderResponse confirmed = created;
    tx.executeWithoutResult(
        s ->
            orders.updateItem(
                item.itemId(),
                ItemStatus.CONFIRMED,
                confirmed.getExternalOrderId(),
                confirmed.getRecordLocator(),
                null,
                clock.instant()));
    // What the platform now owes the supplier for this item, by the provider's settlement method.
    finance.recordPayable(
        order,
        item,
        confirmed.hasCharged() && !confirmed.getCharged().getCurrency().isBlank()
            ? money(confirmed.getCharged())
            : item.total(),
        confirmed.getExternalOrderId());
    metrics.booked(item.offerType(), reconciledByLookup ? "RECONCILED" : "CONFIRMED");
  }

  /** Returns the provider offer id to book: the original, or the re-quoted one after expiry. */
  private String revalidate(RequestContext supplierCtx, Item item) {
    if ("AIR".equals(item.offerType())) {
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
      return item.providerOfferId();
    }
    QuoteOfferResponse quoted =
        suppliers.quote(
            QuoteOfferRequest.newBuilder()
                .setCtx(supplierCtx)
                .setProvider(item.provider())
                .setProviderOfferId(item.providerOfferId())
                .build());
    Money requoted = money(quoted.getOffer().getTotal());
    if (requoted.isGreaterThan(item.total())) {
      throw Status.FAILED_PRECONDITION
          .withDescription(
              "PRICE_CHANGED: " + item.total() + " is now " + requoted + "; re-evaluate policy")
          .asRuntimeException();
    }
    return quoted.getRequoted() && !quoted.getOffer().getProviderOfferId().isBlank()
        ? quoted.getOffer().getProviderOfferId()
        : item.providerOfferId();
  }

  /** What the supplier holds under our key, or null when it never recorded the command. */
  private @Nullable CreateOrderResponse reconcile(RequestContext supplierCtx, Item item) {
    BookingStatus status;
    try {
      status =
          suppliers.bookingStatus(
              GetBookingStatusRequest.newBuilder()
                  .setCtx(supplierCtx)
                  .setProvider(item.provider())
                  .setIdempotencyKey(supplierCtx.getIdempotencyKey())
                  .build());
    } catch (StatusRuntimeException lookup) {
      log.warn("status lookup at {} failed: {}", item.provider(), lookup.getStatus());
      return null;
    }
    if (status.getStatus() != SupplierOrderStatus.CONFIRMED
        && status.getStatus() != SupplierOrderStatus.CHANGED) {
      return null;
    }
    return CreateOrderResponse.newBuilder()
        .setExternalOrderId(status.getExternalOrderId())
        .setRecordLocator(status.getRecordLocator())
        .setStatus(status.getStatus())
        .setCharged(status.getCharged())
        .addAllTicketNumbers(status.getTicketNumbersList())
        .build();
  }

  /** Legs, then stays, then transfers; ties keep the bundle's order. */
  static List<Offer> inBookingOrder(List<Offer> offers) {
    List<Offer> out = new ArrayList<>(offers);
    out.sort(
        java.util.Comparator.comparingInt(
            o ->
                switch (o.getType()) {
                  case AIR -> 0;
                  case HOTEL -> 1;
                  default -> 2;
                }));
    return out;
  }

  /**
   * Compensate whatever was confirmed, then mark the order FAILED with an honest compensated flag.
   */
  private OrderRecord fail(
      RequestContexts.Validated ctx, OrderRecord order, String code, String message) {
    OrderRecord current = orders.find(ctx.tenant(), order.orderId()).orElseThrow();
    boolean allReleased = true;
    List<ExposureRecord> exposed = new ArrayList<>();
    // Reverse booking order: the last thing confirmed is the first thing released.
    List<Item> confirmed =
        new ArrayList<>(
            current.items().stream().filter(i -> i.status() == ItemStatus.CONFIRMED).toList());
    java.util.Collections.reverse(confirmed);
    for (Item item : confirmed) {
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
        metrics.compensated(item.offerType(), "RELEASED");
      } catch (StatusRuntimeException e) {
        metrics.compensated(item.offerType(), "CANCEL_FAILED");
        log.error(
            "compensation failed for order {} item {} ({}): needs human attention",
            current.orderId(),
            item.itemId(),
            item.externalRef(),
            e);
        allReleased = false;
        String cancelCode = failureCode(e);
        ExposureRecord exposure =
            new ExposureRecord(
                Ids.newId(IdPrefix.EXPOSURE),
                current.orderId(),
                item.itemId(),
                item.componentId(),
                item.provider(),
                item.externalRef() == null ? "" : item.externalRef(),
                item.total(),
                "COMPENSATION_FAILED",
                cancelCode
                    + ": "
                    + (e.getStatus().getDescription() == null
                        ? cancelCode
                        : e.getStatus().getDescription()),
                ExposureRecord.Status.OPEN,
                null,
                null,
                null,
                clock.instant(),
                null);
        exposed.add(exposure);
        tx.executeWithoutResult(
            s -> {
              orders.updateItem(
                  item.itemId(), ItemStatus.CANCEL_FAILED, null, null, cancelCode, clock.instant());
              exposures.insert(ctx.tenant(), exposure);
            });
      }
    }
    // Phase 4: an item whose outcome is unknown is money at risk too; its exposure already exists.
    for (Item item : current.items()) {
      if (item.status() == ItemStatus.UNKNOWN) {
        allReleased = false;
        exposures.byOrder(ctx.tenant(), current.orderId()).stream()
            .filter(
                x -> x.itemId().equals(item.itemId()) && x.status() == ExposureRecord.Status.OPEN)
            .forEach(exposed::add);
      }
    }
    boolean compensated = allReleased;
    if (compensated) {
      // nothing is held at any supplier: the authorization is released too
      finance.release(current, code);
    }
    return tx.execute(
        s -> {
          OrderRecord fresh = orders.find(ctx.tenant(), order.orderId()).orElseThrow();
          OrderStatus to = compensated ? OrderStatus.FAILED : OrderStatus.PARTIALLY_FAILED;
          orders.transition(fresh, to, code, null, code, message, compensated, clock.instant());
          OrderRecord failed = orders.find(ctx.tenant(), order.orderId()).orElseThrow();
          outbox.append(
              OrderEvents.failed(failed, code, message, compensated, exposed, null, clock));
          if (!exposed.isEmpty()) {
            outbox.append(
                OrderEvents.compensationFailed(
                    failed, exposed, "COMPENSATION_INCOMPLETE", message, null, clock));
          }
          return failed;
        });
  }

  // ------------------------------------------------------------------ Phase 6: partial release

  /**
   * Releases only the named components at their suppliers; the order keeps its status for the rest.
   * Idempotent by item state: released items are skipped, a refusal is an exposure for a person
   * (never asked again by machine), a lost answer resumes from the item states.
   */
  private OrderRecord releaseComponents(
      RequestContexts.Validated ctx, CancelOrderCommand command, OrderRecord order) {
    if (order.status() != OrderStatus.CONFIRMED && order.status() != OrderStatus.CHANGED) {
      throw Status.FAILED_PRECONDITION
          .withDescription("ORDER_NOT_CONFIRMED: status " + order.status())
          .asRuntimeException();
    }
    java.util.Set<String> wanted = new java.util.HashSet<>(command.getComponentIdsList());
    List<Item> targets =
        order.items().stream()
            .filter(i -> i.componentId() != null && wanted.contains(i.componentId()))
            .toList();
    if (targets.isEmpty()) {
      throw Status.NOT_FOUND
          .withDescription(
              "COMPONENT_UNKNOWN: none of " + wanted + " is part of order " + order.orderId())
          .asRuntimeException();
    }
    String reason = command.getReason().isBlank() ? "component cancelled" : command.getReason();
    List<Item> released = new ArrayList<>();
    List<ExposureRecord> refusedNow = new ArrayList<>();
    for (Item item : targets) {
      if (item.status() != ItemStatus.CONFIRMED) {
        continue; // already released, refused earlier, or never confirmed
      }
      CancelOrderResponse response;
      try {
        response =
            suppliers.cancelOrder(
                CancelOrderRequest.newBuilder()
                    .setCtx(command.getCtx())
                    .setProvider(item.provider())
                    .setExternalOrderId(item.externalRef() == null ? "" : item.externalRef())
                    .build());
      } catch (StatusRuntimeException e) {
        if (SupplierClient.isRetryable(e.getStatus())) {
          throw e;
        }
        String code = failureCode(e);
        metrics.cancelled(item.offerType(), "REFUSED");
        ExposureRecord exposure =
            new ExposureRecord(
                Ids.newId(IdPrefix.EXPOSURE),
                order.orderId(),
                item.itemId(),
                item.componentId(),
                item.provider(),
                item.externalRef() == null ? "" : item.externalRef(),
                item.total(),
                "CANCELLATION_REFUSED",
                code
                    + ": "
                    + (e.getStatus().getDescription() == null
                        ? code
                        : e.getStatus().getDescription()),
                ExposureRecord.Status.OPEN,
                null,
                null,
                null,
                clock.instant(),
                null);
        tx.executeWithoutResult(
            s -> {
              orders.updateItem(
                  item.itemId(), ItemStatus.CANCEL_FAILED, null, null, code, clock.instant());
              exposures.insert(ctx.tenant(), exposure);
            });
        refusedNow.add(exposure);
        continue;
      }
      Money itemRefund =
          response.hasRefund() && !response.getRefund().getCurrency().isBlank()
              ? money(response.getRefund())
              : Money.of(item.total().currency(), 0);
      metrics.cancelled(item.offerType(), "RELEASED");
      tx.executeWithoutResult(
          s -> {
            orders.updateItem(
                item.itemId(), ItemStatus.CANCELLED, null, null, null, clock.instant());
            orders.recordItemRefund(item.itemId(), itemRefund, clock.instant());
          });
      finance.refund(
          order,
          item.itemId(),
          itemRefund,
          "component cancellation refund from " + item.provider(),
          "1");
      if (response.hasCredit() && response.getCredit().getAmountMinor() > 0) {
        finance.issueCredit(
            order,
            item,
            money(response.getCredit()),
            response.getCreditReference().isBlank()
                ? item.externalRef() + ":credit"
                : response.getCreditReference(),
            response.hasCreditExpiresAt()
                ? Instant.ofEpochSecond(response.getCreditExpiresAt().getSeconds())
                : null);
      }
      released.add(item);
    }
    OrderRecord fresh = orders.find(ctx.tenant(), order.orderId()).orElseThrow();
    if (!released.isEmpty() || !refusedNow.isEmpty()) {
      Money refund = Money.of(fresh.total().currency(), 0);
      for (Item i : fresh.items()) {
        if (released.stream().anyMatch(r -> r.itemId().equals(i.itemId()))) {
          Optional<Money> r = orders.refundOf(i.itemId());
          if (r.isPresent() && r.get().currency().equals(refund.currency())) {
            refund = refund.plus(r.get());
          }
        }
      }
      Money total = refund;
      List<Item> releasedNow =
          fresh.items().stream()
              .filter(i -> released.stream().anyMatch(r -> r.itemId().equals(i.itemId())))
              .toList();
      tx.executeWithoutResult(
          s ->
              outbox.append(
                  OrderEvents.itemsReleased(
                      fresh,
                      releasedNow,
                      refusedNow,
                      total,
                      reason,
                      ctx.principal(),
                      command.getCtx().getCausationId(),
                      clock)));
    }
    return fresh;
  }

  // ------------------------------------------------------------------ Phase 5: finance

  public io.travelos.order.finance.FinanceService.Receipt receipt(OrderRecord order) {
    return finance.receipt(order);
  }

  // ------------------------------------------------------------------ Slice 3: exposures

  public List<ExposureRecord> exposuresOf(TenantId tenant, String orderId) {
    return exposures.byOrder(tenant, orderId);
  }

  public List<ExposureRecord> exposures(TenantId tenant, ExposureRecord.Status status, int limit) {
    return exposures.byTenantAndStatus(tenant, status, limit);
  }

  /**
   * A person closes an exposure (cancelled by phone, loss accepted, refund negotiated). Idempotent
   * by the caller's key. When nothing is open any more the order is honest again: FAILED, fully
   * compensated by people.
   */
  public ExposureRecord resolveExposure(
      TenantId tenant,
      String orderId,
      String exposureId,
      Principal by,
      String resolution,
      String idempotencyKey) {
    ExposureRecord exposure =
        exposures
            .find(tenant, exposureId)
            .filter(e -> e.orderId().equals(orderId))
            .orElseThrow(
                () ->
                    Status.NOT_FOUND
                        .withDescription("exposure " + exposureId + " not found")
                        .asRuntimeException());
    if (exposure.status() == ExposureRecord.Status.RESOLVED) {
      if (idempotencyKey.equals(exposure.resolutionIdempotencyKey())) {
        return exposure;
      }
      throw Status.FAILED_PRECONDITION
          .withDescription("EXPOSURE_ALREADY_RESOLVED: by " + exposure.resolvedBy())
          .asRuntimeException();
    }
    Instant now = clock.instant();
    return tx.execute(
        s -> {
          if (!exposures.resolve(tenant, exposureId, by.id(), resolution, idempotencyKey, now)) {
            throw Status.ABORTED
                .withDescription("resolved concurrently; re-read")
                .asRuntimeException();
          }
          ExposureRecord resolved = exposures.find(tenant, exposureId).orElseThrow();
          OrderRecord order = orders.find(tenant, orderId).orElseThrow();
          outbox.append(OrderEvents.exposureResolved(order, resolved, by, null, clock));
          metrics.exposureResolved();
          boolean anyOpen =
              exposures.byOrder(tenant, orderId).stream()
                  .anyMatch(e -> e.status() == ExposureRecord.Status.OPEN);
          if (!anyOpen && order.status() == OrderStatus.PARTIALLY_FAILED) {
            orders.transition(
                order,
                OrderStatus.FAILED,
                "every exposure resolved by " + by.id(),
                null,
                order.failureCode(),
                order.failureMessage(),
                true,
                now);
          } else if (!anyOpen && order.status() == OrderStatus.CANCELLATION_PENDING) {
            // A cancellation a supplier refused is complete once people released the rest: the
            // order is CANCELLED now, and only now, with every refund that was recorded.
            String reason = "every exposure resolved by " + by.id();
            orders.transition(order, OrderStatus.CANCELLED, reason, null, null, null, true, now);
            OrderRecord cancelled = orders.find(tenant, orderId).orElseThrow();
            outbox.append(
                OrderEvents.cancelled(
                    cancelled, orders.refundsOf(orderId).orElse(null), reason, by, null, clock));
          }
          return resolved;
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

  /**
   * Releases a confirmed order at its suppliers, for a person or for the trip's cancellation
   * workflow. The order is CANCELLATION_PENDING before any supplier is called and CANCELLED only
   * once every confirmed item is released, so a crash in between leaves an honest state that a
   * retry continues from: released items are skipped, refused ones are left to the person who owns
   * their exposure (never asked again, never exposed twice). A refusal (a non-refundable rate, a
   * reference the supplier does not know) becomes an OPEN exposure and the order stays
   * CANCELLATION_PENDING with failure code CANCELLATION_INCOMPLETE until a person resolves it
   * ({@link #resolveExposure}); a transient supplier error propagates so the caller retries.
   * Idempotent by state: a cancelled order is returned as it is.
   */
  public OrderRecord cancel(CancelOrderCommand command) {
    RequestContexts.Validated ctx = RequestContexts.require(command.getCtx());
    OrderRecord order = get(ctx.tenant(), command.getOrderId());
    if (command.getComponentIdsCount() > 0) {
      return releaseComponents(ctx, command, order);
    }
    if (order.status() == OrderStatus.CANCELLED) {
      return order;
    }
    String reason = command.getReason().isBlank() ? "cancelled" : command.getReason();
    if (order.status() != OrderStatus.CANCELLATION_PENDING) {
      if (!order.status().canTransitionTo(OrderStatus.CANCELLATION_PENDING)) {
        throw Status.FAILED_PRECONDITION
            .withDescription("ORDER_NOT_CANCELLABLE: status " + order.status())
            .asRuntimeException();
      }
      // The intent is on record before the first supplier hears of it.
      OrderRecord pending = order;
      Boolean moved =
          tx.execute(
              s ->
                  orders.transition(
                      pending,
                      OrderStatus.CANCELLATION_PENDING,
                      reason,
                      null,
                      null,
                      null,
                      false,
                      clock.instant()));
      if (!Boolean.TRUE.equals(moved)) {
        throw Status.ABORTED
            .withDescription("order changed concurrently; retry")
            .asRuntimeException();
      }
    }
    OrderRecord current = orders.find(ctx.tenant(), order.orderId()).orElseThrow();
    // Reverse booking order, confirmed items only: CANCELLED ones are already released and
    // CANCEL_FAILED ones belong to the person resolving their exposure.
    List<Item> confirmed =
        new ArrayList<>(
            current.items().stream().filter(i -> i.status() == ItemStatus.CONFIRMED).toList());
    java.util.Collections.reverse(confirmed);
    List<ExposureRecord> refused = new ArrayList<>();
    for (Item item : confirmed) {
      CancelOrderResponse response;
      try {
        response =
            suppliers.cancelOrder(
                CancelOrderRequest.newBuilder()
                    .setCtx(command.getCtx())
                    .setProvider(item.provider())
                    .setExternalOrderId(item.externalRef() == null ? "" : item.externalRef())
                    .build());
      } catch (StatusRuntimeException e) {
        if (SupplierClient.isRetryable(e.getStatus())) {
          // The answer is not known yet. Everything released so far is recorded; the caller
          // retries and this method continues from the item states.
          throw e;
        }
        String code = failureCode(e);
        metrics.cancelled(item.offerType(), "REFUSED");
        log.warn(
            "order {} item {} ({}) could not be released: {}; exposure recorded for a person",
            current.orderId(),
            item.itemId(),
            item.externalRef(),
            code);
        ExposureRecord exposure =
            new ExposureRecord(
                Ids.newId(IdPrefix.EXPOSURE),
                current.orderId(),
                item.itemId(),
                item.componentId(),
                item.provider(),
                item.externalRef() == null ? "" : item.externalRef(),
                item.total(),
                "CANCELLATION_REFUSED",
                code
                    + ": "
                    + (e.getStatus().getDescription() == null
                        ? code
                        : e.getStatus().getDescription()),
                ExposureRecord.Status.OPEN,
                null,
                null,
                null,
                clock.instant(),
                null);
        refused.add(exposure);
        tx.executeWithoutResult(
            s -> {
              orders.updateItem(
                  item.itemId(), ItemStatus.CANCEL_FAILED, null, null, code, clock.instant());
              exposures.insert(ctx.tenant(), exposure);
            });
        continue;
      }
      Money itemRefund =
          response.hasRefund() && !response.getRefund().getCurrency().isBlank()
              ? money(response.getRefund())
              : Money.of(item.total().currency(), 0);
      metrics.cancelled(item.offerType(), "RELEASED");
      tx.executeWithoutResult(
          s -> {
            orders.updateItem(
                item.itemId(), ItemStatus.CANCELLED, null, null, null, clock.instant());
            orders.recordItemRefund(item.itemId(), itemRefund, clock.instant());
          });
      // Phase 5: the supplier's refund goes back to the instrument; a credit is value kept.
      finance.refund(
          current, item.itemId(), itemRefund, "cancellation refund from " + item.provider(), "1");
      if (response.hasCredit() && response.getCredit().getAmountMinor() > 0) {
        finance.issueCredit(
            current,
            item,
            money(response.getCredit()),
            response.getCreditReference().isBlank()
                ? item.externalRef() + ":credit"
                : response.getCreditReference(),
            response.hasCreditExpiresAt()
                ? Instant.ofEpochSecond(response.getCreditExpiresAt().getSeconds())
                : null);
      }
    }
    return tx.execute(
        s -> {
          OrderRecord fresh = orders.find(ctx.tenant(), order.orderId()).orElseThrow();
          boolean anyRefused =
              fresh.items().stream().anyMatch(i -> i.status() == ItemStatus.CANCEL_FAILED);
          boolean anyOpen =
              exposures.byOrder(ctx.tenant(), fresh.orderId()).stream()
                  .anyMatch(e -> e.status() == ExposureRecord.Status.OPEN);
          if (!anyRefused && !anyOpen) {
            orders.transition(
                fresh, OrderStatus.CANCELLED, reason, null, null, null, false, clock.instant());
            OrderRecord cancelled = orders.find(ctx.tenant(), order.orderId()).orElseThrow();
            outbox.append(
                OrderEvents.cancelled(
                    cancelled,
                    orders.refundsOf(cancelled.orderId()).orElse(null),
                    reason,
                    ctx.principal(),
                    command.getCtx().getCausationId(),
                    clock));
            return cancelled;
          }
          if (refused.isEmpty()) {
            // a retry after an earlier refusal: nothing new happened, nothing new is said
            return fresh;
          }
          String message =
              refused.size()
                  + " component(s) could not be released: "
                  + refused.stream()
                      .map(e -> e.provider() + " " + e.externalRef() + " (" + e.detail() + ")")
                      .collect(java.util.stream.Collectors.joining("; "));
          orders.transition(
              fresh,
              OrderStatus.CANCELLATION_PENDING,
              "cancellation incomplete: " + message,
              null,
              "CANCELLATION_INCOMPLETE",
              message,
              false,
              clock.instant());
          OrderRecord incomplete = orders.find(ctx.tenant(), order.orderId()).orElseThrow();
          outbox.append(
              OrderEvents.compensationFailed(
                  incomplete,
                  refused,
                  "CANCELLATION_INCOMPLETE",
                  message,
                  command.getCtx().getCausationId(),
                  clock));
          return incomplete;
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
    boolean byComponent =
        replacement.getOffersCount() > 0
            && replacement.getOffersList().stream().allMatch(o -> !o.getComponentId().isBlank());
    if (!byComponent
        && (replacement.getOffersCount() != 1
            || replacement.getOffers(0).getType() != OfferType.AIR)) {
      throw Status.FAILED_PRECONDITION
          .withDescription(
              "UNSUPPORTED_CHANGE: a change replaces exactly one AIR item, or components by id")
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
          || (!byComponent && currentOffer.equals(replacement.getOffers(0).getProviderOfferId()))) {
        throw Status.FAILED_PRECONDITION
            .withDescription("SAME_ITINERARY: the replacement is the current itinerary")
            .asRuntimeException();
      }
      OrderRecord current = order;
      change = tx.execute(status -> requestChange(ctx, command, current, key));
    }
    return byComponent
        ? applyComponentChange(ctx, command, change)
        : applyChange(ctx, command, change);
  }

  /**
   * Slice 3: change the components named by the replacement, preserve every other item. Each
   * component is one supplier mutation under its own key (order:change:component), committed as
   * soon as the supplier answers, so a crash between components resumes with nothing repeated: the
   * supplier already holds the reissue for that key and returns it. A vendor that cannot change the
   * booking is cancelled and rebooked under the same discipline.
   */
  private OrderRecord applyComponentChange(
      RequestContexts.Validated ctx, ChangeOrderCommand command, OrderChangeRecord change) {
    OrderRecord order = orders.find(ctx.tenant(), change.orderId()).orElseThrow();
    Money incremental = Money.zero(order.total().currency());
    String externalOrderId = null;
    for (Offer offer : inBookingOrder(command.getReplacement().getOffersList())) {
      String componentId = offer.getComponentId();
      OrderRecord fresh = orders.find(ctx.tenant(), order.orderId()).orElseThrow();
      Item already =
          fresh.items().stream()
              .filter(i -> componentId.equals(i.componentId()))
              .filter(i -> i.status() == ItemStatus.CONFIRMED)
              .filter(i -> i.providerOfferId().equals(offer.getProviderOfferId()))
              .findFirst()
              .orElse(null);
      if (already != null) {
        continue; // this component was changed before the crash; nothing to repeat
      }
      Item current =
          fresh.items().stream()
              .filter(i -> componentId.equals(i.componentId()))
              .filter(i -> i.status() == ItemStatus.CONFIRMED)
              .reduce((a, b) -> b)
              .orElse(null);
      if (current == null || current.externalRef() == null) {
        return failChange(
            ctx,
            fresh,
            change,
            "NO_CONFIRMED_ITEM",
            "component " + componentId + " has no confirmed item to change");
      }
      RequestContext supplierCtx =
          command.getCtx().toBuilder()
              .setIdempotencyKey(order.orderId() + ":" + change.changeId() + ":" + componentId)
              .build();
      Money delta;
      String newRef;
      String newLocator;
      try {
        String providerOfferId = revalidate(supplierCtx, itemFor(offer, current, componentId));
        if (offer.getProvider().equals(current.provider())) {
          ChangeOrderResponse changed = null;
          try {
            changed =
                suppliers.changeOrder(
                    ChangeOrderRequest.newBuilder()
                        .setCtx(supplierCtx)
                        .setProvider(current.provider())
                        .setExternalOrderId(current.externalRef())
                        .setNewProviderOfferId(providerOfferId)
                        .setPaymentToken(command.getPaymentToken())
                        .build());
          } catch (StatusRuntimeException e) {
            if (!"CHANGE_NOT_SUPPORTED".equals(failureCode(e))) {
              throw e;
            }
          }
          if (changed != null) {
            delta = money(changed.getIncrementalCost());
            newRef = changed.getExternalOrderId();
            newLocator = changed.getRecordLocator();
          } else {
            CreateOrderResponse rebooked =
                cancelAndRebook(supplierCtx, command, current, offer, providerOfferId);
            delta = money(rebooked.getCharged()).minus(current.total());
            newRef = rebooked.getExternalOrderId();
            newLocator = rebooked.getRecordLocator();
          }
        } else {
          CreateOrderResponse rebooked =
              cancelAndRebook(supplierCtx, command, current, offer, providerOfferId);
          delta = money(rebooked.getCharged()).minus(current.total());
          newRef = rebooked.getExternalOrderId();
          newLocator = rebooked.getRecordLocator();
        }
      } catch (StatusRuntimeException e) {
        if (isTransient(e.getStatus())) {
          throw e; // the caller retries; components already changed are found and skipped
        }
        String code = failureCode(e);
        log.warn(
            "order {} change {} component {} failed: {}",
            order.orderId(),
            change.changeId(),
            componentId,
            e.getStatus());
        return failChange(
            ctx,
            fresh,
            change,
            code,
            e.getStatus().getDescription() == null ? code : e.getStatus().getDescription());
      }
      incremental = incremental.plus(delta);
      if ("AIR".equals(current.offerType())) {
        externalOrderId = newRef;
      }
      Instant now = clock.instant();
      Item replacementItem =
          new Item(
              Ids.newId(IdPrefix.ORDER_ITEM),
              fresh.items().stream().mapToInt(Item::position).max().orElse(-1) + 1,
              offer.getType().name(),
              offer.getProvider(),
              offer.getProviderOfferId(),
              toJson(offer),
              ItemStatus.CONFIRMED,
              newRef,
              newLocator,
              money(offer.getTotal()),
              null,
              now,
              componentId);
      tx.executeWithoutResult(
          s -> {
            orders.updateItem(current.itemId(), ItemStatus.CHANGED, null, null, null, now);
            orders.insertItem(order.orderId(), ctx.tenant(), replacementItem);
          });
    }
    Money finalIncremental = incremental;
    String finalExternal = externalOrderId;
    // Phase 5: the difference is charged or refunded on the instrument, once per change.
    if (finalIncremental.amountMinor() > 0) {
      finance.captureAdditional(
          order, finalIncremental, change.changeId(), command.getPaymentToken(), ctx.principal());
    } else if (finalIncremental.amountMinor() < 0) {
      finance.refund(
          order,
          null,
          Money.of(finalIncremental.currency(), -finalIncremental.amountMinor()),
          "change " + change.changeId() + " cost less",
          change.changeId());
    }
    Instant now = clock.instant();
    return tx.execute(
        s -> {
          OrderRecord fresh = orders.find(ctx.tenant(), order.orderId()).orElseThrow();
          Money newTotal =
              fresh.items().stream()
                  .filter(i -> i.status() == ItemStatus.CONFIRMED)
                  .map(Item::total)
                  .reduce(Money::plus)
                  .orElse(fresh.total());
          if (fresh.status() == OrderStatus.CHANGE_PENDING
              && !orders.replaceItinerary(
                  fresh,
                  OrderStatus.CHANGED,
                  change.replacementBundleId(),
                  newTotal,
                  finalExternal,
                  "components changed (" + change.changeId() + ")",
                  now)) {
            throw Status.ABORTED
                .withDescription("order changed concurrently; retry")
                .asRuntimeException();
          }
          changes.complete(
              change.changeId(),
              OrderChangeRecord.Status.APPLIED,
              finalIncremental.amountMinor(),
              finalExternal,
              null,
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

  /** A stand-in item for revalidating the replacement offer before touching the supplier. */
  private static Item itemFor(Offer offer, Item current, String componentId) {
    return new Item(
        current.itemId(),
        current.position(),
        offer.getType().name(),
        offer.getProvider(),
        offer.getProviderOfferId(),
        toJson(offer),
        ItemStatus.PENDING,
        null,
        null,
        money(offer.getTotal()),
        null,
        current.updatedAt(),
        componentId);
  }

  /** Cancel the current booking and book the replacement, each under its own idempotent key. */
  private CreateOrderResponse cancelAndRebook(
      RequestContext supplierCtx,
      ChangeOrderCommand command,
      Item current,
      Offer offer,
      String providerOfferId) {
    suppliers.cancelOrder(
        CancelOrderRequest.newBuilder()
            .setCtx(
                supplierCtx.toBuilder()
                    .setIdempotencyKey(supplierCtx.getIdempotencyKey() + ":CANCEL"))
            .setProvider(current.provider())
            .setExternalOrderId(current.externalRef())
            .build());
    return suppliers.createOrder(
        CreateOrderRequest.newBuilder()
            .setCtx(
                supplierCtx.toBuilder()
                    .setIdempotencyKey(supplierCtx.getIdempotencyKey() + ":CREATE"))
            .setProvider(offer.getProvider())
            .setProviderOfferId(providerOfferId)
            .addAllPassengers(command.getPassengersList())
            .setPaymentToken(command.getPaymentToken())
            .build());
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
