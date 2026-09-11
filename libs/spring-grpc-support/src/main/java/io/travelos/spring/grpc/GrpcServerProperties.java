package io.travelos.spring.grpc;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param port TCP port; 0 picks a free port (tests). Convention: HTTP 808x pairs with gRPC 908x.
 * @param enabled false disables the server entirely (e.g. a service that only consumes gRPC)
 * @param shutdownGrace how long in-flight calls may finish before shutdownNow
 */
@ConfigurationProperties(prefix = "travelos.grpc.server")
public record GrpcServerProperties(Integer port, Boolean enabled, Duration shutdownGrace) {

  public GrpcServerProperties {
    port = port == null ? 9090 : port;
    enabled = enabled == null || enabled;
    shutdownGrace = shutdownGrace == null ? Duration.ofSeconds(10) : shutdownGrace;
  }
}
