package io.travelos.supplier;

import io.travelos.contracts.supplier.v1.BookingStatus;
import io.travelos.contracts.supplier.v1.CancelOrderRequest;
import io.travelos.contracts.supplier.v1.CancelOrderResponse;
import io.travelos.contracts.supplier.v1.ChangeOrderRequest;
import io.travelos.contracts.supplier.v1.ChangeOrderResponse;
import io.travelos.contracts.supplier.v1.CreateOrderRequest;
import io.travelos.contracts.supplier.v1.CreateOrderResponse;
import io.travelos.contracts.supplier.v1.GetBookingStatusRequest;
import io.travelos.contracts.supplier.v1.QuoteOfferRequest;
import io.travelos.contracts.supplier.v1.QuoteOfferResponse;
import io.travelos.contracts.supplier.v1.SupplierCapabilities;
import io.travelos.supplier.AirSupplier.SupplierException;

/**
 * What every adapter, whatever it sells, implements in OUR vocabulary (Slice 3). Air adapters add
 * flight search and notices ({@link AirSupplier}); hotel and ground adapters add their searches.
 * Nothing above this line ever sees a vendor payload shape.
 *
 * <p>Every mutation is idempotent by {@code ctx.idempotency_key}, and {@link #bookingStatus} lets a
 * caller find out what happened to a command whose answer it never received before it retries.
 */
public interface SupplierAdapter {
  /** Adapter id, e.g. {@code sandbox-air}, {@code sandbox-hotel}. Appears as Offer.provider. */
  String provider();

  SupplierCapabilities capabilities();

  /** Revalidate an offer before a mutation: still sold, at what price, until when. */
  QuoteOfferResponse quote(QuoteOfferRequest request);

  CreateOrderResponse createOrder(CreateOrderRequest request);

  ChangeOrderResponse changeOrder(ChangeOrderRequest request);

  CancelOrderResponse cancelOrder(CancelOrderRequest request);

  /** NOT_FOUND status when the supplier never recorded the command: retrying is safe. */
  default BookingStatus bookingStatus(GetBookingStatusRequest request) {
    throw new SupplierException(
        "NOT_IMPLEMENTED", provider() + " cannot look bookings up by reference", false);
  }
}
