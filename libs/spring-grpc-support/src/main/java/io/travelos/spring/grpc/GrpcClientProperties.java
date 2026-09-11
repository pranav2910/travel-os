package io.travelos.spring.grpc;

import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Named client channels: {@code travelos.grpc.clients.<name>.address=host:port}. Plaintext inside
 * the cluster for Slice 1; TLS/mTLS lands with the mesh. Names are the service names other code
 * asks {@link GrpcChannels} for, e.g. {@code supplier-gateway}, {@code policy}, {@code
 * optimization}.
 */
@ConfigurationProperties(prefix = "travelos.grpc")
public record GrpcClientProperties(Map<String, Client> clients) {

  public GrpcClientProperties {
    clients = clients == null ? Map.of() : Map.copyOf(clients);
  }

  public record Client(String address, Duration deadline) {
    public Client {
      deadline = deadline == null ? Duration.ofSeconds(10) : deadline;
    }
  }
}
