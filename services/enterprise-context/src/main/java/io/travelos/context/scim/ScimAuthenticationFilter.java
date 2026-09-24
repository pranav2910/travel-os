package io.travelos.context.scim;

import io.travelos.common.identity.Principal;
import io.travelos.common.tenant.TenantId;
import io.travelos.context.IntegrationProperties;
import io.travelos.spring.web.auth.RequestPrincipal;
import io.travelos.spring.web.auth.TravelOsAuthentication;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * A provisioning token names exactly one tenant. The principal it yields is the tenant's
 * provisioning service with the travel admin's authority over employees, nothing more.
 */
final class ScimAuthenticationFilter extends OncePerRequestFilter {
  private final Map<String, String> tokens;

  ScimAuthenticationFilter(IntegrationProperties.Scim scim) {
    this.tokens = scim.tokens();
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String header = request.getHeader("Authorization");
    if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
      String presented = header.substring(7).trim();
      for (Map.Entry<String, String> e : tokens.entrySet()) {
        if (e.getValue() != null
            && !e.getValue().isBlank()
            && constantTimeEquals(e.getValue(), presented)) {
          RequestPrincipal principal =
              new RequestPrincipal(
                  new Principal.Service("scim-" + e.getKey()),
                  TenantId.of(e.getKey()),
                  null,
                  Set.of("TRAVEL_ADMIN", "PROVISIONER"),
                  null,
                  null,
                  null);
          SecurityContextHolder.getContext()
              .setAuthentication(
                  new TravelOsAuthentication(
                      null,
                      principal,
                      List.of(
                          new SimpleGrantedAuthority("ROLE_TRAVEL_ADMIN"),
                          new SimpleGrantedAuthority("ROLE_PROVISIONER"))));
          break;
        }
      }
    }
    chain.doFilter(request, response);
  }

  private static boolean constantTimeEquals(String a, String b) {
    return MessageDigest.isEqual(
        a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
  }
}
