package io.travelos.spring.web.auth;

import java.util.Collection;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/** The Spring Security {@code Authentication} whose principal is a {@link RequestPrincipal}. */
public final class TravelOsAuthentication extends AbstractAuthenticationToken {

  private final transient Jwt jwt;
  private final RequestPrincipal principal;

  public TravelOsAuthentication(
      Jwt jwt, RequestPrincipal principal, Collection<? extends GrantedAuthority> authorities) {
    super(authorities);
    this.jwt = jwt;
    this.principal = principal;
    setAuthenticated(true);
  }

  @Override
  public RequestPrincipal getPrincipal() {
    return principal;
  }

  @Override
  public Jwt getCredentials() {
    return jwt;
  }

  @Override
  public String getName() {
    return principal.principal().id();
  }
}
