package io.travelos.supplier;

import io.travelos.contracts.offer.v1.OfferType;
import io.travelos.contracts.supplier.v1.CancelOrderRequest;
import io.travelos.contracts.supplier.v1.CancelOrderResponse;
import io.travelos.contracts.supplier.v1.ChangeOrderRequest;
import io.travelos.contracts.supplier.v1.ChangeOrderResponse;
import io.travelos.contracts.supplier.v1.CreateOrderRequest;
import io.travelos.contracts.supplier.v1.CreateOrderResponse;
import io.travelos.contracts.supplier.v1.PriceOfferRequest;
import io.travelos.contracts.supplier.v1.PriceOfferResponse;
import io.travelos.contracts.supplier.v1.QuoteOfferRequest;
import io.travelos.contracts.supplier.v1.QuoteOfferResponse;
import io.travelos.contracts.supplier.v1.SearchAirRequest;
import io.travelos.contracts.supplier.v1.SearchAirResponse;
import io.travelos.contracts.supplier.v1.SupplierCapabilities;
import io.travelos.supplier.notification.SupplierNotification;

/**
 * What every air adapter implements, in OUR normalized vocabulary. An NDC or GDS adapter maps its
 * vendor payloads to these messages and nothing above this line ever sees a vendor shape.
 *
 * <p>Failures are {@link SupplierException}s with a stable code and a retryable flag; the gateway
 * decides what to do with them (retry, open the breaker, report partial results).
 */
public interface AirSupplier extends SupplierAdapter {

  /** Adapter id, e.g. {@code sandbox-air}, {@code ndc-delta}. Appears as Offer.provider. */
  @Override
  String provider();

  SearchAirResponse search(SearchAirRequest request);

  PriceOfferResponse price(PriceOfferRequest request);

  @Override
  CreateOrderResponse createOrder(CreateOrderRequest request);

  @Override
  ChangeOrderResponse changeOrder(ChangeOrderRequest request);

  @Override
  CancelOrderResponse cancelOrder(CancelOrderRequest request);

  /** Air adapters revalidate through {@link #price}; the general form is the same answer. */
  @Override
  default QuoteOfferResponse quote(QuoteOfferRequest request) {
    PriceOfferResponse priced =
        price(
            PriceOfferRequest.newBuilder()
                .setCtx(request.getCtx())
                .setProvider(request.getProvider())
                .setProviderOfferId(request.getProviderOfferId())
                .build());
    return QuoteOfferResponse.newBuilder()
        .setOffer(priced.getOffer())
        .setPriceChanged(priced.getPriceChanged())
        .build();
  }

  @Override
  default SupplierCapabilities capabilities() {
    return SupplierCapabilities.newBuilder()
        .setProvider(provider())
        .addTypes(OfferType.AIR)
        .setChangeSupported(true)
        .setCancelSupported(true)
        .setStatusLookupSupported(false)
        .setNotificationsSupported(true)
        .setIntegration("LIVE")
        .build();
  }

  /**
   * Turn a raw supplier notice (the vendor's webhook body) into our normalized notification. Pure:
   * no side effects, so it can be called again for a redelivered webhook.
   */
  default SupplierNotification normalizeNotification(String rawPayload) {
    throw new SupplierException(
        "NOTIFICATIONS_NOT_SUPPORTED", provider() + " does not deliver notices", false);
  }

  /**
   * Called exactly once per new notice, inside the gateway's transaction. A real adapter has
   * nothing to do (the airline already changed its own inventory); the sandbox uses it to become
   * the airline that cancelled the flight and priced the reaccommodation.
   */
  default void applyNotification(SupplierNotification notice, String rawPayload) {}

  final class SupplierException extends RuntimeException {
    private final String code;
    private final boolean retryable;

    public SupplierException(String code, String message, boolean retryable) {
      super(message);
      this.code = code;
      this.retryable = retryable;
    }

    public String code() {
      return code;
    }

    public boolean retryable() {
      return retryable;
    }
  }
}
