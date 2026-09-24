package io.travelos.context.source.live;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * The three OAuth 2.0 grants the enterprise adapters use, with a small in-memory cache per key.
 * Tokens are never logged.
 */
public final class OAuth2Tokens {
  private record Cached(String token, Instant expiresAt) {}

  private final RestClient http;
  private final Clock clock;
  private final Map<String, Cached> cache = new ConcurrentHashMap<>();

  public OAuth2Tokens(RestClient http, Clock clock) {
    this.http = http;
    this.clock = clock;
  }

  /** RFC 6749 §4.4 client credentials (Microsoft Graph, Salesforce connected apps). */
  public String clientCredentials(
      String cacheKey,
      String tokenUrl,
      String clientId,
      String clientSecret,
      @Nullable String scope) {
    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("grant_type", "client_credentials");
    form.add("client_id", clientId);
    form.add("client_secret", clientSecret);
    if (scope != null) {
      form.add("scope", scope);
    }
    return cached(cacheKey, () -> post(tokenUrl, form));
  }

  /** RFC 6749 §6 refresh token (SAP Concur). */
  public String refresh(
      String cacheKey, String tokenUrl, String clientId, String clientSecret, String refreshToken) {
    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("grant_type", "refresh_token");
    form.add("client_id", clientId);
    form.add("client_secret", clientSecret);
    form.add("refresh_token", refreshToken);
    return cached(cacheKey, () -> post(tokenUrl, form));
  }

  /** RFC 7523 JWT bearer (Google service accounts, with domain-wide delegation via {@code sub}). */
  public String jwtBearer(
      String cacheKey,
      String tokenUrl,
      String issuer,
      String privateKeyPem,
      String scope,
      @Nullable String subject) {
    return cached(
        cacheKey,
        () -> {
          Instant now = clock.instant();
          Map<String, Object> claims = new LinkedHashMap<>();
          claims.put("iss", issuer);
          claims.put("scope", scope);
          claims.put("aud", tokenUrl);
          claims.put("iat", now.getEpochSecond());
          claims.put("exp", now.plusSeconds(3600).getEpochSecond());
          if (subject != null) {
            claims.put("sub", subject);
          }
          String assertion = signRs256(claims, privateKeyPem);
          MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
          form.add("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer");
          form.add("assertion", assertion);
          return post(tokenUrl, form);
        });
  }

  private String cached(String key, java.util.function.Supplier<Cached> fetch) {
    Cached c = cache.get(key);
    if (c != null && c.expiresAt().isAfter(clock.instant().plusSeconds(60))) {
      return c.token();
    }
    Cached fresh = fetch.get();
    cache.put(key, fresh);
    return fresh.token();
  }

  private Cached post(String tokenUrl, MultiValueMap<String, String> form) {
    JsonNode body =
        IntegrationHttp.parse(
            http.post()
                .uri(tokenUrl)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(String.class));
    String token = body.path("access_token").asString("");
    if (token.isBlank()) {
      throw new io.travelos.context.source.SourceException(
          "CREDENTIALS_REVOKED", "the token endpoint returned no access token", false);
    }
    long expires = body.path("expires_in").asLong(3600);
    return new Cached(token, clock.instant().plusSeconds(Math.max(60, expires)));
  }

  static String signRs256(Map<String, Object> claims, String privateKeyPem) {
    try {
      String header =
          base64Url("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
      String payload =
          base64Url(
              IntegrationHttp.JSON.writeValueAsString(claims).getBytes(StandardCharsets.UTF_8));
      String signingInput = header + "." + payload;
      String pem =
          privateKeyPem
              .replace("-----BEGIN PRIVATE KEY-----", "")
              .replace("-----END PRIVATE KEY-----", "")
              .replaceAll("\\s", "");
      PrivateKey key =
          KeyFactory.getInstance("RSA")
              .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
      Signature signature = Signature.getInstance("SHA256withRSA");
      signature.initSign(key);
      signature.update(signingInput.getBytes(StandardCharsets.UTF_8));
      return signingInput + "." + base64Url(signature.sign());
    } catch (java.security.GeneralSecurityException e) {
      throw new io.travelos.context.source.SourceException(
          "CREDENTIALS_INVALID", "the service account key cannot sign: " + e.getMessage(), false);
    }
  }

  private static String base64Url(byte[] bytes) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
