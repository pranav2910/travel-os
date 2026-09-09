package io.travelos.travelcore.api;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.function.Consumer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Mints RS256 tokens the way Keycloak would, against a key pair generated per JVM, and supplies the
 * matching {@link JwtDecoder} (issuer + audience validated, like production). The full bearer-token
 * filter chain therefore runs in tests — nothing is mocked around security.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestTokens {

  public static final String ISSUER = "http://test-issuer/realms/travelos";
  public static final String AUDIENCE = "travelos-api";

  private static final RSAKey KEY;

  static {
    try {
      KEY = new RSAKeyGenerator(2048).keyID("test").generate();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @Bean
  @Primary
  JwtDecoder testJwtDecoder() throws Exception {
    NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(KEY.toRSAPublicKey()).build();
    OAuth2TokenValidator<Jwt> audience =
        new JwtClaimValidator<List<String>>(
            JwtClaimNames.AUD, aud -> aud != null && aud.contains(AUDIENCE));
    decoder.setJwtValidator(
        JwtValidators.createDefaultWithValidators(
            List.of(new JwtIssuerValidator(ISSUER), audience)));
    return decoder;
  }

  /** alice@acme, TRAVELER. */
  public static String alice() {
    return token(
        b ->
            b.subject("alice")
                .claim("preferred_username", "alice")
                .claim("tenant_id", "acme")
                .claim("employee_id", "emp_1001")
                .claim("roles", List.of("TRAVELER")));
  }

  /** bob@acme, MANAGER + TRAVELER. */
  public static String bob() {
    return token(
        b ->
            b.subject("bob")
                .claim("preferred_username", "bob")
                .claim("tenant_id", "acme")
                .claim("employee_id", "emp_1002")
                .claim("roles", List.of("TRAVELER", "MANAGER")));
  }

  /** dan@acme, another plain TRAVELER. */
  public static String dan() {
    return token(
        b ->
            b.subject("dan")
                .claim("preferred_username", "dan")
                .claim("tenant_id", "acme")
                .claim("employee_id", "emp_1004")
                .claim("roles", List.of("TRAVELER")));
  }

  /** zoe@globex, TRAVELER — the other tenant. */
  public static String zoe() {
    return token(
        b ->
            b.subject("zoe")
                .claim("preferred_username", "zoe")
                .claim("tenant_id", "globex")
                .claim("employee_id", "emp_2001")
                .claim("roles", List.of("TRAVELER")));
  }

  public static String token(Consumer<JWTClaimsSet.Builder> customizer) {
    return token(ISSUER, AUDIENCE, customizer);
  }

  public static String token(
      String issuer, String audience, Consumer<JWTClaimsSet.Builder> customizer) {
    try {
      Instant now = Instant.now();
      JWTClaimsSet.Builder claims =
          new JWTClaimsSet.Builder()
              .issuer(issuer)
              .audience(audience)
              .issueTime(Date.from(now))
              .expirationTime(Date.from(now.plusSeconds(300)));
      customizer.accept(claims);
      SignedJWT jwt =
          new SignedJWT(
              new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).build(),
              claims.build());
      JWSSigner signer = new RSASSASigner(KEY);
      jwt.sign(signer);
      return jwt.serialize();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
