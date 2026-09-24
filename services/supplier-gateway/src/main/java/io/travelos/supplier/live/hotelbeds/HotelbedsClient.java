package io.travelos.supplier.live.hotelbeds;

import io.travelos.supplier.live.LiveHttp;
import io.travelos.supplier.live.LiveIntegrationProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * Hotelbeds APItude (hotel-api 1.0). Every request carries the API key and a signature
 * SHA-256(apiKey + secret + unix seconds); neither the key nor the secret ever appears in logs.
 */
public class HotelbedsClient {
  static final String PROVIDER = "hotelbeds";

  private final RestClient http;
  private final LiveIntegrationProperties.Hotelbeds props;
  private final Clock clock;

  public HotelbedsClient(
      RestClient.Builder builder, LiveIntegrationProperties.Hotelbeds props, Clock clock) {
    this.props = props;
    this.clock = clock;
    this.http =
        builder
            .baseUrl(props.baseUrl())
            .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE)
            .defaultHeader("Accept-Encoding", "identity")
            .build();
  }

  String signature() {
    long seconds = clock.instant().getEpochSecond();
    try {
      MessageDigest sha = MessageDigest.getInstance("SHA-256");
      return HexFormat.of()
          .formatHex(
              sha.digest(
                  (props.apiKey() + props.secret() + seconds).getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public JsonNode post(String path, JsonNode body) {
    try {
      String response =
          http.post()
              .uri(path)
              .header("Api-key", props.apiKey())
              .header("X-Signature", signature())
              .contentType(MediaType.APPLICATION_JSON)
              .body(LiveHttp.JSON.writeValueAsString(body))
              .retrieve()
              .body(String.class);
      return LiveHttp.parse(response);
    } catch (RuntimeException e) {
      throw LiveHttp.failure(PROVIDER, e);
    }
  }

  public JsonNode get(String path) {
    try {
      String response =
          http.get()
              .uri(path)
              .header("Api-key", props.apiKey())
              .header("X-Signature", signature())
              .retrieve()
              .body(String.class);
      return LiveHttp.parse(response);
    } catch (RuntimeException e) {
      throw LiveHttp.failure(PROVIDER, e);
    }
  }

  public JsonNode delete(String path) {
    try {
      String response =
          http.delete()
              .uri(path)
              .header("Api-key", props.apiKey())
              .header("X-Signature", signature())
              .retrieve()
              .body(String.class);
      return LiveHttp.parse(response);
    } catch (RuntimeException e) {
      throw LiveHttp.failure(PROVIDER, e);
    }
  }
}
