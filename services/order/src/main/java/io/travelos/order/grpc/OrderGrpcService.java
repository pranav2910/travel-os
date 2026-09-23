package io.travelos.order.grpc;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Timestamp;
import com.google.protobuf.util.JsonFormat;
import io.grpc.stub.StreamObserver;
import io.travelos.contracts.offer.v1.Offer;
import io.travelos.contracts.order.v1.CancelOrderCommand;
import io.travelos.contracts.order.v1.ChangeOrderCommand;
import io.travelos.contracts.order.v1.CreateOrderCommand;
import io.travelos.contracts.order.v1.Exposure;
import io.travelos.contracts.order.v1.FindOrderByExternalRefRequest;
import io.travelos.contracts.order.v1.GetOrderRequest;
import io.travelos.contracts.order.v1.Order;
import io.travelos.contracts.order.v1.OrderChange;
import io.travelos.contracts.order.v1.OrderItem;
import io.travelos.contracts.order.v1.OrderItemStatus;
import io.travelos.contracts.order.v1.OrderServiceGrpc;
import io.travelos.contracts.order.v1.OrderStatus;
import io.travelos.order.saga.OrderService;
import io.travelos.order.store.ExposureRecord;
import io.travelos.order.store.OrderChangeRecord;
import io.travelos.order.store.OrderRecord;
import io.travelos.spring.grpc.RequestContexts;
import java.time.Instant;
import org.springframework.stereotype.Component;

@Component
public class OrderGrpcService extends OrderServiceGrpc.OrderServiceImplBase {

  private final OrderService orders;

  public OrderGrpcService(OrderService orders) {
    this.orders = orders;
  }

  @Override
  public void createOrder(CreateOrderCommand request, StreamObserver<Order> observer) {
    observer.onNext(withChanges(orders.create(request)));
    observer.onCompleted();
  }

  @Override
  public void getOrder(GetOrderRequest request, StreamObserver<Order> observer) {
    RequestContexts.Validated ctx = RequestContexts.require(request.getCtx());
    observer.onNext(withChanges(orders.get(ctx.tenant(), request.getOrderId())));
    observer.onCompleted();
  }

  @Override
  public void cancelOrder(CancelOrderCommand request, StreamObserver<Order> observer) {
    observer.onNext(withChanges(orders.cancel(request)));
    observer.onCompleted();
  }

  @Override
  public void changeOrder(ChangeOrderCommand request, StreamObserver<Order> observer) {
    OrderRecord changed = orders.change(request);
    observer.onNext(withChanges(changed));
    observer.onCompleted();
  }

  @Override
  public void findOrderByExternalRef(
      FindOrderByExternalRefRequest request, StreamObserver<Order> observer) {
    RequestContexts.Validated ctx = RequestContexts.require(request.getCtx());
    if (request.getSupplier().isBlank() || request.getExternalOrderId().isBlank()) {
      throw io.grpc.Status.INVALID_ARGUMENT
          .withDescription("supplier and external_order_id are required")
          .asRuntimeException();
    }
    observer.onNext(
        withChanges(
            orders.findByExternalRef(
                ctx.tenant(), request.getSupplier(), request.getExternalOrderId())));
    observer.onCompleted();
  }

  private Order withChanges(OrderRecord o) {
    Order.Builder b = toProto(o).toBuilder();
    for (OrderChangeRecord c : orders.changesOf(o.tenant(), o.orderId())) {
      b.addChanges(toProto(c));
    }
    for (ExposureRecord e : orders.exposuresOf(o.tenant(), o.orderId())) {
      b.addExposures(toProto(e));
    }
    return b.build();
  }

  public static Exposure toProto(ExposureRecord e) {
    Exposure.Builder b =
        Exposure.newBuilder()
            .setExposureId(e.exposureId())
            .setItemId(e.itemId())
            .setComponentId(nullToEmpty(e.componentId()))
            .setProvider(e.provider())
            .setExternalRef(e.externalRef())
            .setAmount(money(e.amount()))
            .setReason(e.reason())
            .setDetail(nullToEmpty(e.detail()))
            .setStatus(e.status().name())
            .setResolvedBy(nullToEmpty(e.resolvedBy()))
            .setResolution(nullToEmpty(e.resolution()))
            .setCreatedAt(ts(e.createdAt()));
    if (e.resolvedAt() != null) {
      b.setResolvedAt(ts(e.resolvedAt()));
    }
    return b.build();
  }

  public static OrderChange toProto(OrderChangeRecord c) {
    OrderChange.Builder b =
        OrderChange.newBuilder()
            .setChangeId(c.changeId())
            .setDisruptionId(nullToEmpty(c.disruptionId()))
            .setIdempotencyKey(c.idempotencyKey())
            .setStatus(c.status().name())
            .setPreviousBundleId(c.previousBundleId())
            .setReplacementBundleId(c.replacementBundleId())
            .setPolicyDecisionId(nullToEmpty(c.policyDecisionId()))
            .setOptimizationRunId(nullToEmpty(c.optimizationRunId()))
            .setApprovalId(nullToEmpty(c.approvalId()))
            .setExternalOrderId(nullToEmpty(c.externalOrderId()))
            .setRecordLocator(nullToEmpty(c.recordLocator()))
            .setFailureCode(nullToEmpty(c.failureCode()))
            .setCreatedAt(ts(c.createdAt()))
            .setUpdatedAt(ts(c.updatedAt()));
    if (c.incrementalMinor() != null) {
      b.setIncrementalCost(
          io.travelos.contracts.common.v1.Money.newBuilder()
              .setCurrency(c.currency())
              .setAmountMinor(c.incrementalMinor()));
    }
    return b.build();
  }

  public static Order toProto(OrderRecord o) {
    Order.Builder b =
        Order.newBuilder()
            .setOrderId(o.orderId())
            .setTenantId(o.tenant().value())
            .setTripId(o.tripId())
            .setSupplier(o.supplier())
            .setExternalOrderId(o.externalOrderId() == null ? "" : o.externalOrderId())
            .setStatus(OrderStatus.valueOf(o.status().name()))
            .setTotal(money(o.total()))
            .setIdempotencyKey(o.idempotencyKey())
            .setPolicyDecisionId(nullToEmpty(o.policyDecisionId()))
            .setOptimizationRunId(nullToEmpty(o.optimizationRunId()))
            .setApprovalId(nullToEmpty(o.approvalId()))
            .setVersion(o.version())
            .setCreatedAt(ts(o.createdAt()))
            .setUpdatedAt(ts(o.updatedAt()))
            .setFailureCode(nullToEmpty(o.failureCode()))
            .setCompensated(o.compensated())
            .setTravelerId(o.travelerId())
            .setBundleId(o.bundleId());
    for (OrderRecord.Item item : o.items()) {
      b.addItems(
          OrderItem.newBuilder()
              .setItemId(item.itemId())
              .setOffer(offer(item.offerJson()))
              .setStatus(OrderItemStatus.valueOf("ITEM_" + item.status().name()))
              .setExternalRef(nullToEmpty(item.externalRef()))
              .setRecordLocator(nullToEmpty(item.recordLocator()))
              .setComponentId(nullToEmpty(item.componentId()))
              .setTotal(money(item.total()))
              .setFailureCode(nullToEmpty(item.failureCode())));
    }
    return b.build();
  }

  private static Offer offer(String json) {
    try {
      Offer.Builder builder = Offer.newBuilder();
      JsonFormat.parser().ignoringUnknownFields().merge(json, builder);
      return builder.build();
    } catch (InvalidProtocolBufferException e) {
      throw new IllegalStateException("stored offer is not valid protobuf JSON", e);
    }
  }

  private static io.travelos.contracts.common.v1.Money money(io.travelos.common.money.Money m) {
    return io.travelos.contracts.common.v1.Money.newBuilder()
        .setCurrency(m.currency())
        .setAmountMinor(m.amountMinor())
        .build();
  }

  private static Timestamp ts(Instant i) {
    return Timestamp.newBuilder().setSeconds(i.getEpochSecond()).setNanos(i.getNano()).build();
  }

  private static String nullToEmpty(String s) {
    return s == null ? "" : s;
  }
}
