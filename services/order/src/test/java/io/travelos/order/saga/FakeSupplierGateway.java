package io.travelos.order.saga;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.travelos.contracts.common.v1.Money;
import io.travelos.contracts.supplier.v1.CancelOrderRequest;
import io.travelos.contracts.supplier.v1.CancelOrderResponse;
import io.travelos.contracts.supplier.v1.CreateOrderRequest;
import io.travelos.contracts.supplier.v1.CreateOrderResponse;
import io.travelos.contracts.supplier.v1.PriceOfferRequest;
import io.travelos.contracts.supplier.v1.PriceOfferResponse;
import io.travelos.contracts.supplier.v1.SupplierGatewayGrpc;
import io.travelos.contracts.supplier.v1.SupplierOrderStatus;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A scripted Supplier Gateway on a real port. Behaviour is keyed by provider_offer_id so one test
 * can mix well-behaved and misbehaving components in a single bundle:
 *
 * <ul>
 *   <li>{@code ok-*}: prices and books fine
 *   <li>{@code flaky-*}: UNAVAILABLE on the first two CreateOrder attempts, then fine
 *   <li>{@code soldout-*}: FAILED_PRECONDITION SEAT_NO_LONGER_AVAILABLE
 *   <li>{@code pricier-*}: reprices 20% higher
 *   <li>{@code stuck-*}: books fine but cancellation fails (compensation cannot complete)
 * </ul>
 */
final class FakeSupplierGateway extends SupplierGatewayGrpc.SupplierGatewayImplBase {

  final Map<String, CreateOrderResponse> ordersByKey = new ConcurrentHashMap<>();
  final Map<String, String> offerByExternalId = new ConcurrentHashMap<>();
  final Map<String, AtomicInteger> createAttempts = new ConcurrentHashMap<>();
  final List<String> cancelled = new ArrayList<>();
  private Server server;

  int start() throws IOException {
    server = ServerBuilder.forPort(0).addService(this).build().start();
    return server.getPort();
  }

  void stop() {
    server.shutdownNow();
  }

  @Override
  public void priceOffer(PriceOfferRequest request, StreamObserver<PriceOfferResponse> observer) {
    String id = request.getProviderOfferId();
    long cents = cents(id);
    if (id.startsWith("pricier-")) {
      cents = cents * 12 / 10;
    }
    observer.onNext(
        PriceOfferResponse.newBuilder()
            .setOffer(
                io.travelos.contracts.offer.v1.Offer.newBuilder()
                    .setProvider(request.getProvider())
                    .setProviderOfferId(id)
                    .setTotal(usd(cents)))
            .setPriceChanged(id.startsWith("pricier-"))
            .build());
    observer.onCompleted();
  }

  @Override
  public void createOrder(
      CreateOrderRequest request, StreamObserver<CreateOrderResponse> observer) {
    String id = request.getProviderOfferId();
    String key = request.getCtx().getIdempotencyKey();
    int attempt = createAttempts.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
    if (id.startsWith("flaky-") && attempt <= 2) {
      observer.onError(Status.UNAVAILABLE.withDescription("simulated blip").asRuntimeException());
      return;
    }
    if (id.startsWith("soldout-")) {
      observer.onError(
          Status.FAILED_PRECONDITION
              .withDescription("SEAT_NO_LONGER_AVAILABLE: the last seat was just sold")
              .asRuntimeException());
      return;
    }
    CreateOrderResponse response =
        ordersByKey.computeIfAbsent(
            key,
            k ->
                CreateOrderResponse.newBuilder()
                    .setExternalOrderId("EXT-" + Integer.toHexString(k.hashCode()))
                    .setRecordLocator("LOC" + (ordersByKey.size() + 100))
                    .setStatus(SupplierOrderStatus.CONFIRMED)
                    .setCharged(usd(cents(id)))
                    .addTicketNumbers("0067" + Math.abs(k.hashCode()))
                    .build());
    offerByExternalId.put(response.getExternalOrderId(), id);
    observer.onNext(response);
    observer.onCompleted();
  }

  @Override
  public void cancelOrder(
      CancelOrderRequest request, StreamObserver<CancelOrderResponse> observer) {
    String external = request.getExternalOrderId();
    if (offerByExternalId.getOrDefault(external, "").startsWith("stuck-")) {
      observer.onError(
          Status.UNAVAILABLE.withDescription("cancellation system down").asRuntimeException());
      return;
    }
    synchronized (cancelled) {
      cancelled.add(external);
    }
    observer.onNext(
        CancelOrderResponse.newBuilder()
            .setExternalOrderId(external)
            .setStatus(SupplierOrderStatus.CANCELLED)
            .setRefund(usd(40000))
            .build());
    observer.onCompleted();
  }

  static long cents(String providerOfferId) {
    return 40000 + Math.floorMod(providerOfferId.hashCode(), 20000);
  }

  private static Money usd(long cents) {
    return Money.newBuilder().setCurrency("USD").setAmountMinor(cents).build();
  }
}
