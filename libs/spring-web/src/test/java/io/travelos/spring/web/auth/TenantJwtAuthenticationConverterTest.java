package io.travelos.spring.web.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.travelos.common.identity.Principal;
import io.travelos.common.tenant.TenantId;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

class TenantJwtAuthenticationConverterTest {

  private final TenantJwtAuthenticationConverter converter = new TenantJwtAuthenticationConverter();

  @Test
  void mapsClaimsToTenantScopedPrincipal() {
    TravelOsAuthentication auth =
        (TravelOsAuthentication)
            converter.convert(
                jwt(
                    b ->
                        b.claim("tenant_id", "acme")
                            .claim("employee_id", "emp_1001")
                            .claim("preferred_username", "Alice")
                            .claim("roles", List.of("TRAVELER", "MANAGER"))));

    RequestPrincipal me = auth.getPrincipal();
    assertThat(me.principal()).isEqualTo(new Principal.Human("alice"));
    assertThat(me.tenant()).isEqualTo(TenantId.of("acme"));
    assertThat(me.employeeId()).isEqualTo("emp_1001");
    assertThat(me.roles()).containsExactlyInAnyOrder("TRAVELER", "MANAGER");
    assertThat(auth.getAuthorities().stream().map(GrantedAuthority::getAuthority))
        .containsExactlyInAnyOrder("ROLE_TRAVELER", "ROLE_MANAGER");
    assertThat(auth.isAuthenticated()).isTrue();
    assertThat(auth.getName()).isEqualTo("human/alice");
  }

  @Test
  void fallsBackToSubjectWhenNoUsername() {
    TravelOsAuthentication auth =
        (TravelOsAuthentication)
            converter.convert(
                jwt(
                    b ->
                        b.subject("6777986d-496f-4de4-8cb0-1a15c86fdfe5")
                            .claim("tenant_id", "acme")));
    assertThat(auth.getPrincipal().principal().id())
        .isEqualTo("human/6777986d-496f-4de4-8cb0-1a15c86fdfe5");
    assertThat(auth.getPrincipal().roles()).isEmpty();
    assertThat(auth.getPrincipal().employeeId()).isNull();
  }

  @Test
  void rejectsTokensWithoutATenant() {
    assertThatThrownBy(() -> converter.convert(jwt(b -> b.claim("preferred_username", "alice"))))
        .isInstanceOf(InvalidBearerTokenException.class)
        .hasMessageContaining("tenant_id");
    assertThatThrownBy(() -> converter.convert(jwt(b -> b.claim("tenant_id", "Acme Corp"))))
        .isInstanceOf(InvalidBearerTokenException.class);
  }

  private static Jwt jwt(Consumer<Jwt.Builder> customizer) {
    Jwt.Builder builder =
        Jwt.withTokenValue("token")
            .header("alg", "none")
            .subject("subject")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(60));
    customizer.accept(builder);
    return builder.build();
  }
}
