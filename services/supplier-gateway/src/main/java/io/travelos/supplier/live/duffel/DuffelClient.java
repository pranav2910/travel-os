package io.travelos.supplier.live.duffel;

import io.travelos.supplier.live.LiveHttp;
import io.travelos.supplier.live.LiveIntegrationProperties;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * Duffel's REST API (v2), as much of it as the adapter needs. The token never appears in logs or
 * errors. Every method returns the {@code data} node or throws a {@code SupplierException}.
 */
public class DuffelClient {
  static final String PROVIDER = "duffel";

  private final RestClient http;

  public DuffelClient(RestClient.Builder builder, LiveIntegrationProperties.Duffel props) {
    this.http =
        builder
            .baseUrl(props.baseUrl())
            .defaultHeader("Authorization", "Bearer " + props.accessToken())
            .defaultHeader("Duffel-Version", "v2")
            .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE)
            .build();
  }

  public JsonNode post(String path, JsonNode body) {
    try {
      String response =
          http.post()
              .uri(path)
              .contentType(MediaType.APPLICATION_JSON)
              .body(LiveHttp.JSON.writeValueAsString(body))
              .retrieve()
              .body(String.class);
      return LiveHttp.parse(response).path("data");
    } catch (RuntimeException e) {
      throw LiveHttp.failure(PROVIDER, e);
    }
  }

  public JsonNode get(String path) {
    try {
      String response = http.get().uri(path).retrieve().body(String.class);
      return LiveHttp.parse(response).path("data");
    } catch (RuntimeException e) {
      throw LiveHttp.failure(PROVIDER, e);
    }
  }
}
