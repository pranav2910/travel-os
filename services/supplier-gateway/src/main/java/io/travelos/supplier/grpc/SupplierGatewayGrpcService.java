package io.travelos.supplier.grpc;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.travelos.contracts.supplier.v1.CancelOrderRequest;
import io.travelos.contracts.supplier.v1.CancelOrderResponse;
import io.travelos.contracts.supplier.v1.ChangeOrderRequest;
import io.travelos.contracts.supplier.v1.ChangeOrderResponse;
import io.travelos.contracts.supplier.v1.CreateOrderRequest;
import io.travelos.contracts.supplier.v1.CreateOrderResponse;
import io.travelos.contracts.supplier.v1.PriceOfferRequest;
import io.travelos.contracts.supplier.v1.PriceOfferResponse;
import io.travelos.contracts.supplier.v1.SearchAirRequest;
import io.travelos.contracts.supplier.v1.SearchAirResponse;
import io.travelos.contracts.supplier.v1.SupplierError;
import io.travelos.contracts.supplier.v1.SupplierGatewayGrpc;
import io.travelos.spring.grpc.RequestContexts;
import io.travelos.supplier.AirSupplier.SupplierException;
import io.travelos.supplier.SupplierRegistry;
import io.travelos.supplier.notification.SupplierOrderRefRepository;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Search fans out to every adapter and returns partial results with per-adapter errors: one
 * supplier being down must not fail the search. Order operations target the offer's provider and
 * translate supplier failures into gRPC statuses that say whether retrying makes sense.
 */
@Component
public class SupplierGatewayGrpcService extends SupplierGatewayGrpc.SupplierGatewayImplBase {

  private static final Logger log = LoggerFactory.getLogger(SupplierGatewayGrpcService.class);

  private final SupplierRegistry registry;
  private final SupplierOrderRefRepository refs;
  private final Clock clock;

  public SupplierGatewayGrpcService(
      SupplierRegistry registry, SupplierOrderRefRepository refs, Clock clock) {
    this.registry = registry;
    this.refs = refs;
    this.clock = clock;
  }

  @Override
  public void searchAir(SearchAirRequest request, StreamObserver<SearchAirResponse> observer) {
    RequestContexts.require(request.getCtx());
    SearchAirResponse.Builder merged = SearchAirResponse.newBuilder();
    for (String provider : registry.providers()) {
      try {
        SearchAirResponse partial = registry.call(provider, s -> s.search(request));
        if (merged.getSearchSessionId().isEmpty()) {
          merged.setSearchSessionId(partial.getSearchSessionId());
        }
        merged.addAllOffers(partial.getOffersList());
      } catch (SupplierException e) {
        log.warn(
            "search via {} failed: {} ({}retryable)",
            provider,
            e.code(),
            e.retryable() ? "" : "not ");
        merged.addErrors(
            SupplierError.newBuilder()
                .setProvider(provider)
                .setCode(e.code())
                .setMessage(e.getMessage())
                .setRetryable(e.retryable()));
      }
    }
    if (merged.getSearchSessionId().isEmpty()) {
      merged.setSearchSessionId(
          io.travelos.common.ids.Ids.newId(io.travelos.common.ids.IdPrefix.SEARCH_SESSION));
    }
    observer.onNext(merged.build());
    observer.onCompleted();
  }

  @Override
  public void priceOffer(PriceOfferRequest request, StreamObserver<PriceOfferResponse> observer) {
    RequestContexts.require(request.getCtx());
    observer.onNext(guarded(request.getProvider(), s -> s.price(request)));
    observer.onCompleted();
  }

  @Override
  public void createOrder(
      CreateOrderRequest request, StreamObserver<CreateOrderResponse> observer) {
    RequestContexts.require(request.getCtx());
    CreateOrderResponse response = guarded(request.getProvider(), s -> s.createOrder(request));
    // The door remembers what it booked: a later supplier notice about this order is tied back to
    // the trip (correlation id) without asking anyone.
    refs.remember(
        new SupplierOrderRefRepository.Ref(
            request.getProvider(),
            response.getExternalOrderId(),
            request.getCtx().getTenantId(),
            request.getCtx().getCorrelationId(),
            response.getRecordLocator(),
            clock.instant()));
    observer.onNext(response);
    observer.onCompleted();
  }

  @Override
  public void changeOrder(
      ChangeOrderRequest request, StreamObserver<ChangeOrderResponse> observer) {
    RequestContexts.require(request.getCtx());
    observer.onNext(guarded(request.getProvider(), s -> s.changeOrder(request)));
    observer.onCompleted();
  }

  @Override
  public void cancelOrder(
      CancelOrderRequest request, StreamObserver<CancelOrderResponse> observer) {
    RequestContexts.require(request.getCtx());
    observer.onNext(guarded(request.getProvider(), s -> s.cancelOrder(request)));
    observer.onCompleted();
  }

  private <T> T guarded(
      String provider, java.util.function.Function<io.travelos.supplier.AirSupplier, T> call) {
    try {
      return registry.call(provider, call);
    } catch (SupplierException e) {
      throw status(e).asRuntimeException();
    }
  }

  /** Retryable supplier trouble is UNAVAILABLE (callers may retry); everything else is final. */
  static Status status(SupplierException e) {
    String description = e.code() + ": " + e.getMessage();
    return switch (e.code()) {
      case "PROVIDER_UNKNOWN", "OFFER_UNKNOWN", "ORDER_UNKNOWN" ->
          Status.NOT_FOUND.withDescription(description);
      case "OFFER_EXPIRED",
          "SEAT_NO_LONGER_AVAILABLE",
          "PAYMENT_DECLINED",
          "FLIGHT_CANCELLED",
          "ORDER_CANCELLED" ->
          Status.FAILED_PRECONDITION.withDescription(description);
      case "NOT_IMPLEMENTED" -> Status.UNIMPLEMENTED.withDescription(description);
      case "RATE_LIMITED" -> Status.RESOURCE_EXHAUSTED.withDescription(description);
      default ->
          e.retryable()
              ? Status.UNAVAILABLE.withDescription(description)
              : Status.INVALID_ARGUMENT.withDescription(description);
    };
  }
}
