package io.travelos.order.grpc;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Timestamp;
import com.google.protobuf.util.JsonFormat;
import io.grpc.stub.StreamObserver;
import io.travelos.contracts.offer.v1.Offer;
import io.travelos.contracts.order.v1.CancelOrderCommand;
import io.travelos.contracts.order.v1.CreateOrderCommand;
import io.travelos.contracts.order.v1.GetOrderRequest;
import io.travelos.contracts.order.v1.Order;
import io.travelos.contracts.order.v1.OrderItem;
import io.travelos.contracts.order.v1.OrderItemStatus;
import io.travelos.contracts.order.v1.OrderServiceGrpc;
import io.travelos.contracts.order.v1.OrderStatus;
import io.travelos.order.saga.OrderService;
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
    observer.onNext(toProto(orders.create(request)));
    observer.onCompleted();
  }

  @Override
  public void getOrder(GetOrderRequest request, StreamObserver<Order> observer) {
    RequestContexts.Validated ctx = RequestContexts.require(request.getCtx());
    observer.onNext(toProto(orders.get(ctx.tenant(), request.getOrderId())));
    observer.onCompleted();
  }

  @Override
  public void cancelOrder(CancelOrderCommand request, StreamObserver<Order> observer) {
    observer.onNext(toProto(orders.cancel(request)));
    observer.onCompleted();
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
            .setUpdatedAt(ts(o.updatedAt()));
    for (OrderRecord.Item item : o.items()) {
      b.addItems(
          OrderItem.newBuilder()
              .setItemId(item.itemId())
              .setOffer(offer(item.offerJson()))
              .setStatus(OrderItemStatus.valueOf("ITEM_" + item.status().name()))
              .setExternalRef(nullToEmpty(item.externalRef()))
              .setRecordLocator(nullToEmpty(item.recordLocator())));
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
