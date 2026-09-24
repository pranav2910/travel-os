package io.travelos.supplier.ledger;

import com.google.protobuf.Message;
import com.google.protobuf.Parser;
import io.travelos.contracts.supplier.v1.BookingStatus;
import io.travelos.contracts.supplier.v1.CancelOrderResponse;
import io.travelos.contracts.supplier.v1.ChangeOrderResponse;
import io.travelos.contracts.supplier.v1.CreateOrderResponse;
import io.travelos.contracts.supplier.v1.SupplierOrderStatus;
import org.jspecify.annotations.Nullable;

/** What a booking-status answer means for each command whose answer was lost. */
final class Reconciliation {
  private Reconciliation() {}

  @SuppressWarnings("unchecked")
  static <T extends Message> @Nullable T fromStatus(
      MutationAttempt.Command command, BookingStatus status, Parser<T> parser) {
    Message m =
        switch (command) {
          case CREATE ->
              status.getStatus() == SupplierOrderStatus.CONFIRMED
                      || status.getStatus() == SupplierOrderStatus.HELD
                      || status.getStatus() == SupplierOrderStatus.CHANGED
                  ? CreateOrderResponse.newBuilder()
                      .setExternalOrderId(status.getExternalOrderId())
                      .setRecordLocator(status.getRecordLocator())
                      .setStatus(status.getStatus())
                      .setCharged(status.getCharged())
                      .addAllTicketNumbers(status.getTicketNumbersList())
                      .build()
                  : null;
          case CANCEL ->
              status.getStatus() == SupplierOrderStatus.CANCELLED
                  ? CancelOrderResponse.newBuilder()
                      .setExternalOrderId(status.getExternalOrderId())
                      .setStatus(SupplierOrderStatus.CANCELLED)
                      .build()
                  : null;
          case CHANGE ->
              status.getStatus() == SupplierOrderStatus.CHANGED
                  ? ChangeOrderResponse.newBuilder()
                      .setExternalOrderId(status.getExternalOrderId())
                      .setStatus(SupplierOrderStatus.CHANGED)
                      .setRecordLocator(status.getRecordLocator())
                      .addAllTicketNumbers(status.getTicketNumbersList())
                      .setChargedTotal(status.getCharged())
                      .build()
                  : null;
        };
    if (m == null) {
      return null;
    }
    try {
      return parser.parseFrom(m.toByteArray());
    } catch (com.google.protobuf.InvalidProtocolBufferException e) {
      throw new IllegalStateException(e);
    }
  }
}
