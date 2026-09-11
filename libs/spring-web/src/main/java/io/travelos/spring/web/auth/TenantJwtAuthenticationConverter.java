package io.travelos.spring.web.auth;

import io.travelos.common.identity.Principal;
import io.travelos.common.tenant.TenantId;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

/**
 * Turns a validated JWT into a {@link TravelOsAuthentication}. Claims contract (see the Keycloak
 * realm in platform/local): {@code tenant_id} (required), {@code employee_id}, {@code roles[]},
 * {@code preferred_username}. Roles become {@code ROLE_<name>} authorities for method security.
 */
public final class TenantJwtAuthenticationConverter
    implements Converter<Jwt, AbstractAuthenticationToken> {

  public static final String TENANT_CLAIM = "tenant_id";
  public static final String EMPLOYEE_CLAIM = "employee_id";
  public static final String ROLES_CLAIM = "roles";
  public static final String USERNAME_CLAIM = "preferred_username";

  @Override
  public AbstractAuthenticationToken convert(Jwt jwt) {
    String tenantClaim = jwt.getClaimAsString(TENANT_CLAIM);
    if (tenantClaim == null || tenantClaim.isBlank()) {
      throw new InvalidBearerTokenException("token carries no " + TENANT_CLAIM + " claim");
    }
    TenantId tenant;
    try {
      tenant = TenantId.of(tenantClaim);
    } catch (IllegalArgumentException e) {
      throw new InvalidBearerTokenException("malformed " + TENANT_CLAIM + " claim", e);
    }

    String subject = jwt.getClaimAsString(USERNAME_CLAIM);
    if (subject == null || subject.isBlank()) {
      subject = jwt.getSubject();
    }
    Principal principal;
    try {
      principal = new Principal.Human(subject.toLowerCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new InvalidBearerTokenException("subject is not a valid principal name", e);
    }

    List<String> roleClaims = jwt.getClaimAsStringList(ROLES_CLAIM);
    Set<String> roles = roleClaims == null ? Set.of() : Set.copyOf(roleClaims);
    Set<GrantedAuthority> authorities =
        roles.stream()
            .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
            .collect(Collectors.toUnmodifiableSet());

    RequestPrincipal request =
        new RequestPrincipal(
            principal,
            tenant,
            jwt.getClaimAsString(EMPLOYEE_CLAIM),
            roles,
            jwt.getClaimAsString("given_name"),
            jwt.getClaimAsString("family_name"),
            jwt.getClaimAsString("email"));
    return new TravelOsAuthentication(jwt, request, authorities);
  }
}
