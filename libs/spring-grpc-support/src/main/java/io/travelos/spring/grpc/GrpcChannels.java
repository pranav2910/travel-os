package io.travelos.spring.grpc;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.micrometer.core.instrument.binder.grpc.ObservationGrpcClientInterceptor;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.DisposableBean;

/** One lazily created, shared channel per configured client name. Closed with the context. */
public final class GrpcChannels implements DisposableBean {

  private final GrpcClientProperties properties;
  private final @Nullable ObservationRegistry observations;
  private final Map<String, ManagedChannel> channels = new ConcurrentHashMap<>();

  public GrpcChannels(GrpcClientProperties properties) {
    this(properties, null);
  }

  /** With an observation registry every call is a client span and carries traceparent metadata. */
  public GrpcChannels(GrpcClientProperties properties, @Nullable ObservationRegistry observations) {
    this.properties = properties;
    this.observations = observations;
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
          ManagedChannelBuilder<?> builder =
              ManagedChannelBuilder.forTarget(client.address())
                  .usePlaintext()
                  .intercept(new MdcClientInterceptor());
          if (observations != null) {
            builder.intercept(new ObservationGrpcClientInterceptor(observations));
          }
          return builder.build();
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
