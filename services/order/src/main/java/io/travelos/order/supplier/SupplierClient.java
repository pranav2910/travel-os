package io.travelos.order.supplier;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.travelos.contracts.supplier.v1.CancelOrderRequest;
import io.travelos.contracts.supplier.v1.CancelOrderResponse;
import io.travelos.contracts.supplier.v1.CreateOrderRequest;
import io.travelos.contracts.supplier.v1.CreateOrderResponse;
import io.travelos.contracts.supplier.v1.PriceOfferRequest;
import io.travelos.contracts.supplier.v1.PriceOfferResponse;
import io.travelos.contracts.supplier.v1.SupplierGatewayGrpc;
import io.travelos.spring.grpc.GrpcChannels;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * The Order service's view of the Supplier Gateway: every call has a deadline, and retryable
 * statuses (UNAVAILABLE, RESOURCE_EXHAUSTED) are retried a bounded number of times with jittered
 * backoff. Everything else is final and propagates. CreateOrder is safe to retry because the
 * gateway is idempotent by ctx.idempotency_key.
 */
@Component
public class SupplierClient {

  private static final Logger log = LoggerFactory.getLogger(SupplierClient.class);
  static final String CLIENT = "supplier-gateway";

  @ConfigurationProperties(prefix = "travelos.supplier")
  public record RetryProperties(Integer maxAttempts, Duration backoff) {
    public RetryProperties {
      maxAttempts = maxAttempts == null ? 3 : maxAttempts;
      backoff = backoff == null ? Duration.ofMillis(300) : backoff;
    }
  }

  private final GrpcChannels channels;
  private final RetryProperties retry;

  public SupplierClient(GrpcChannels channels, RetryProperties retry) {
    this.channels = channels;
    this.retry = retry;
  }

  public PriceOfferResponse price(PriceOfferRequest request) {
    return withRetry("PriceOffer", () -> stub().priceOffer(request));
  }

  public CreateOrderResponse createOrder(CreateOrderRequest request) {
    return withRetry("CreateOrder", () -> stub().createOrder(request));
  }

  public CancelOrderResponse cancelOrder(CancelOrderRequest request) {
    return withRetry("CancelOrder", () -> stub().cancelOrder(request));
  }

  private SupplierGatewayGrpc.SupplierGatewayBlockingStub stub() {
    return SupplierGatewayGrpc.newBlockingStub(channels.channel(CLIENT))
        .withDeadlineAfter(channels.deadline(CLIENT).toMillis(), TimeUnit.MILLISECONDS);
  }

  private <T> T withRetry(String operation, Supplier<T> call) {
    int attempt = 0;
    while (true) {
      attempt++;
      try {
        return call.get();
      } catch (StatusRuntimeException e) {
        if (!isRetryable(e.getStatus()) || attempt >= retry.maxAttempts()) {
          throw e;
        }
        long sleep =
            retry.backoff().toMillis() * (1L << (attempt - 1))
                + ThreadLocalRandom.current().nextLong(retry.backoff().toMillis());
        log.warn(
            "{} attempt {}/{} failed with {}; retrying in {}ms",
            operation,
            attempt,
            retry.maxAttempts(),
            e.getStatus().getCode(),
            sleep);
        try {
          Thread.sleep(sleep);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw e;
        }
      }
    }
  }

  static boolean isRetryable(Status status) {
    return switch (status.getCode()) {
      case UNAVAILABLE, RESOURCE_EXHAUSTED, DEADLINE_EXCEEDED -> true;
      default -> false;
    };
  }
}
