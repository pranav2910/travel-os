package io.travelos.spring.web.logging;

import io.travelos.spring.web.auth.RequestPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Puts {@code tenant_id} and {@code principal} into the logging MDC for the duration of the
 * request, so every log line of a request is attributable without repeating it at each call site.
 * Runs after bearer-token authentication.
 */
public final class MdcContextFilter extends OncePerRequestFilter {

  public static final String TENANT_KEY = "tenant_id";
  public static final String PRINCIPAL_KEY = "principal";

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    boolean populated = false;
    if (authentication != null && authentication.getPrincipal() instanceof RequestPrincipal me) {
      MDC.put(TENANT_KEY, me.tenant().value());
      MDC.put(PRINCIPAL_KEY, me.principal().id());
      populated = true;
    }
    try {
      chain.doFilter(request, response);
    } finally {
      if (populated) {
        MDC.remove(TENANT_KEY);
        MDC.remove(PRINCIPAL_KEY);
      }
    }
  }
}
