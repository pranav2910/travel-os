package io.travelos.disruption;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.travelos.contracts.common.v1.Money;
import io.travelos.contracts.offer.v1.Offer;
import io.travelos.contracts.offer.v1.OfferType;
import io.travelos.contracts.order.v1.FindOrderByExternalRefRequest;
import io.travelos.contracts.order.v1.Order;
import io.travelos.contracts.order.v1.OrderItem;
import io.travelos.contracts.order.v1.OrderItemStatus;
import io.travelos.contracts.order.v1.OrderServiceGrpc;
import io.travelos.contracts.order.v1.OrderStatus;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** A scripted Order service: knows a few supplier references, one of them in another tenant. */
final class FakeOrderService extends OrderServiceGrpc.OrderServiceImplBase {

  final Map<String, Order> byRef = new ConcurrentHashMap<>();

  /** While true, every lookup fails as if the Order service were down. */
  volatile boolean down;

  private Server server;

  int start() throws IOException {
    server = ServerBuilder.forPort(0).addService(this).build().start();
    return server.getPort();
  }

  void stop() {
    server.shutdownNow();
  }

  void know(String tenant, String externalRef, String tripId, String orderId, String travelerId) {
    byRef.put(
        tenant + "/" + externalRef,
        Order.newBuilder()
            .setOrderId(orderId)
            .setTenantId(tenant)
            .setTripId(tripId)
            .setTravelerId(travelerId)
            .setBundleId("bdl_01ARZ3NDEKTSV4RRFFQ69G5FA0")
            .setSupplier("sandbox-air")
            .setExternalOrderId(externalRef)
            .setStatus(OrderStatus.CONFIRMED)
            .setTotal(Money.newBuilder().setCurrency("USD").setAmountMinor(49558))
            .addItems(
                OrderItem.newBuilder()
                    .setItemId("itm_1")
                    .setStatus(OrderItemStatus.ITEM_CONFIRMED)
                    .setExternalRef(externalRef)
                    .setRecordLocator("TM3KA6")
                    .setOffer(
                        Offer.newBuilder()
                            .setOfferId("off_01ARZ3NDEKTSV4RRFFQ69G5FA0")
                            .setProvider("sandbox-air")
                            .setProviderOfferId("SBX-orig")
                            .setType(OfferType.AIR)
                            .setTotal(Money.newBuilder().setCurrency("USD").setAmountMinor(49558))))
            .build());
  }

  @Override
  public void findOrderByExternalRef(
      FindOrderByExternalRefRequest request, StreamObserver<Order> observer) {
    if (down) {
      observer.onError(
          Status.UNAVAILABLE.withDescription("order service is down").asRuntimeException());
      return;
    }
    Order order = byRef.get(request.getCtx().getTenantId() + "/" + request.getExternalOrderId());
    if (order == null) {
      observer.onError(
          Status.NOT_FOUND.withDescription("no order for that reference").asRuntimeException());
      return;
    }
    observer.onNext(order);
    observer.onCompleted();
  }
}
