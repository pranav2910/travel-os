package io.travelos.spring.web.testing;

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
 * matching {@link JwtDecoder} (issuer + audience validated, like production). Import this class
 * into a {@code @SpringBootTest} and the full bearer-token filter chain runs against real tokens.
 *
 * <p>The dev users mirror platform/local/keycloak/travelos-realm.json plus {@code dan}, a second
 * plain traveler at acme for "same tenant, different person" cases.
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

  /** alice@acme, TRAVELER, emp_1001. */
  public static String alice() {
    return user("alice", "acme", "emp_1001", List.of("TRAVELER"));
  }

  /** bob@acme, TRAVELER + MANAGER, emp_1002. */
  public static String bob() {
    return user("bob", "acme", "emp_1002", List.of("TRAVELER", "MANAGER"));
  }

  /** carol@acme, TRAVELER + TRAVEL_ADMIN + FINANCE, emp_1003. */
  public static String carol() {
    return user("carol", "acme", "emp_1003", List.of("TRAVELER", "TRAVEL_ADMIN", "FINANCE"));
  }

  /** dan@acme, another plain TRAVELER, emp_1004. */
  public static String dan() {
    return user("dan", "acme", "emp_1004", List.of("TRAVELER"));
  }

  /** zoe@globex, TRAVELER, emp_2001: the other tenant. */
  public static String zoe() {
    return user("zoe", "globex", "emp_2001", List.of("TRAVELER"));
  }

  public static String user(String username, String tenant, String employeeId, List<String> roles) {
    String given = Character.toUpperCase(username.charAt(0)) + username.substring(1);
    return token(
        b ->
            b.subject(username)
                .claim("preferred_username", username)
                .claim("tenant_id", tenant)
                .claim("employee_id", employeeId)
                .claim("roles", roles)
                .claim("given_name", given)
                .claim("family_name", "Traveler")
                .claim("email", username + "@" + tenant + ".example"));
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
