package io.travelos.spring.grpc;

import io.grpc.BindableService;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerInterceptor;
import io.grpc.protobuf.services.HealthStatusManager;
import io.grpc.protobuf.services.ProtoReflectionServiceV1;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Starts one grpc-java server after the application context is ready and stops it gracefully on
 * shutdown. Health and reflection are always served (reflection makes grpcurl work locally).
 */
public final class GrpcServerLifecycle implements SmartLifecycle {

  private static final Logger log = LoggerFactory.getLogger(GrpcServerLifecycle.class);

  private final GrpcServerProperties properties;
  private final List<BindableService> services;
  private final List<ServerInterceptor> interceptors;
  private final HealthStatusManager health;
  private @Nullable Server server;

  public GrpcServerLifecycle(
      GrpcServerProperties properties,
      List<BindableService> services,
      List<ServerInterceptor> interceptors,
      HealthStatusManager health) {
    this.properties = properties;
    this.services = List.copyOf(services);
    this.interceptors = List.copyOf(interceptors);
    this.health = health;
  }

  @Override
  public void start() {
    if (server != null) {
      return;
    }
    ServerBuilder<?> builder = ServerBuilder.forPort(properties.port());
    for (ServerInterceptor interceptor : interceptors) {
      builder.intercept(interceptor);
    }
    for (BindableService service : services) {
      builder.addService(service);
    }
    builder.addService(health.getHealthService());
    builder.addService(ProtoReflectionServiceV1.newInstance());
    try {
      server = builder.build().start();
    } catch (IOException e) {
      throw new IllegalStateException("gRPC server failed to bind port " + properties.port(), e);
    }
    services.forEach(
        service ->
            health.setStatus(
                service.bindService().getServiceDescriptor().getName(),
                io.grpc.health.v1.HealthCheckResponse.ServingStatus.SERVING));
    log.info(
        "gRPC server listening on port {} serving {}",
        server.getPort(),
        services.stream()
            .map(service -> service.bindService().getServiceDescriptor().getName())
            .toList());
  }

  @Override
  public void stop() {
    Server running = server;
    if (running == null) {
      return;
    }
    health.enterTerminalState();
    running.shutdown();
    try {
      if (!running.awaitTermination(properties.shutdownGrace().toMillis(), TimeUnit.MILLISECONDS)) {
        running.shutdownNow();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      running.shutdownNow();
    }
    server = null;
  }

  @Override
  public boolean isRunning() {
    return server != null && !server.isShutdown();
  }

  /** Start late (after web server) and stop early. */
  @Override
  public int getPhase() {
    return Integer.MAX_VALUE - 100;
  }

  /** The bound port; equals the configured port unless 0 was requested. */
  public int port() {
    Server running = server;
    if (running == null) {
      throw new IllegalStateException("gRPC server is not running");
    }
    return running.getPort();
  }
}
