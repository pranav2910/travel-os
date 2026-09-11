package io.travelos.spring.grpc;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.DisposableBean;

/** One lazily created, shared channel per configured client name. Closed with the context. */
public final class GrpcChannels implements DisposableBean {

  private final GrpcClientProperties properties;
  private final Map<String, ManagedChannel> channels = new ConcurrentHashMap<>();

  public GrpcChannels(GrpcClientProperties properties) {
    this.properties = properties;
  }

  public ManagedChannel channel(String name) {
    return channels.computeIfAbsent(
        name,
        n -> {
          GrpcClientProperties.Client client = properties.clients().get(n);
          if (client == null || client.address() == null || client.address().isBlank()) {
            throw new IllegalStateException(
                "no gRPC client configured: set travelos.grpc.clients." + n + ".address");
          }
          return ManagedChannelBuilder.forTarget(client.address())
              .usePlaintext()
              .intercept(new MdcClientInterceptor())
              .build();
        });
  }

  /** The per-call deadline configured for this client (default 10s). */
  public Duration deadline(String name) {
    GrpcClientProperties.Client client = properties.clients().get(name);
    return client == null ? Duration.ofSeconds(10) : client.deadline();
  }

  @Override
  public void destroy() {
    channels.values().forEach(ManagedChannel::shutdown);
    channels
        .values()
        .forEach(
            channel -> {
              try {
                if (!channel.awaitTermination(5, TimeUnit.SECONDS)) {
                  channel.shutdownNow();
                }
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                channel.shutdownNow();
              }
            });
  }
}
