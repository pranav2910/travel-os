package io.travelos.disruption.orders;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.travelos.contracts.common.v1.Principal;
import io.travelos.contracts.common.v1.RequestContext;
import io.travelos.contracts.order.v1.FindOrderByExternalRefRequest;
import io.travelos.contracts.order.v1.Order;
import io.travelos.contracts.order.v1.OrderServiceGrpc;
import io.travelos.spring.grpc.GrpcChannels;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/** Impacted-trip detection asks the Order service, the transactional truth, never a cache. */
@Component
public class OrderLookup {

  public static final String PRINCIPAL = "service/disruption";
  private static final String CLIENT = "order";

  private final GrpcChannels channels;

  public OrderLookup(GrpcChannels channels) {
    this.channels = channels;
  }

  /** Phase 6: the order by id, for a traveler's change request. Empty when unknown. */
  public Optional<Order> byId(String tenantId, String correlationId, String orderId) {
    try {
      return Optional.of(
          OrderServiceGrpc.newBlockingStub(channels.channel(CLIENT))
              .withDeadlineAfter(channels.deadline(CLIENT).toMillis(), TimeUnit.MILLISECONDS)
              .getOrder(
                  io.travelos.contracts.order.v1.GetOrderRequest.newBuilder()
                      .setCtx(
                          RequestContext.newBuilder()
                              .setTenantId(tenantId)
                              .setCorrelationId(correlationId)
                              .setPrincipal(
                                  Principal.newBuilder()
                                      .setKind(Principal.Kind.SERVICE)
                                      .setId(PRINCIPAL)))
                      .setOrderId(orderId)
                      .build()));
    } catch (StatusRuntimeException e) {
      if (e.getStatus().getCode() == Status.Code.NOT_FOUND) {
        return Optional.empty();
      }
      throw e;
    }
  }

  /**
   * Empty when the reference is not one of ours; other failures propagate (the consumer retries).
   */
  public Optional<Order> byExternalRef(
      String tenantId, String correlationId, String supplier, String externalOrderId) {
    try {
      return Optional.of(
          OrderServiceGrpc.newBlockingStub(channels.channel(CLIENT))
              .withDeadlineAfter(channels.deadline(CLIENT).toMillis(), TimeUnit.MILLISECONDS)
              .findOrderByExternalRef(
                  FindOrderByExternalRefRequest.newBuilder()
                      .setCtx(
                          RequestContext.newBuilder()
                              .setTenantId(tenantId)
                              .setCorrelationId(correlationId)
                              .setPrincipal(
                                  Principal.newBuilder()
                                      .setKind(Principal.Kind.SERVICE)
                                      .setId(PRINCIPAL)))
                      .setSupplier(supplier)
                      .setExternalOrderId(externalOrderId)
                      .build()));
    } catch (StatusRuntimeException e) {
      if (e.getStatus().getCode() == Status.Code.NOT_FOUND) {
        return Optional.empty();
      }
      throw e;
    }
  }
}
