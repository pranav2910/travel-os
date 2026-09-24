package io.travelos.context.scim;

import io.travelos.context.IntegrationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;

/**
 * Phase 7: the identity provider provisions over /scim/v2 with a per-tenant bearer token from the
 * secrets mechanism, not with a user's JWT. Everything else keeps the platform's resource-server
 * chain (libs/spring-web).
 */
@Configuration(proxyBeanMethods = false)
class ScimSecurityConfiguration {

  @Bean
  @Order(0)
  SecurityFilterChain scimSecurityFilterChain(HttpSecurity http, IntegrationProperties properties)
      throws Exception {
    http.securityMatcher("/scim/**")
        .csrf(csrf -> csrf.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(r -> r.anyRequest().authenticated())
        .addFilterBefore(
            new ScimAuthenticationFilter(properties.scim()), BasicAuthenticationFilter.class)
        .httpBasic(basic -> basic.disable())
        .formLogin(Customizer.withDefaults())
        .formLogin(form -> form.disable())
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint(
                    (request, response, ex) -> {
                      response.setStatus(401);
                      response.setContentType("application/scim+json");
                      response
                          .getWriter()
                          .write(
                              "{\"schemas\":[\"urn:ietf:params:scim:api:messages:2.0:Error\"],\"status\":\"401\",\"detail\":\"a valid provisioning token is required\"}");
                    }));
    return http.build();
  }
}
