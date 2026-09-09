package io.travelos.spring.web.auth;

import io.travelos.spring.web.logging.MdcContextFilter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Stateless JWT resource server. Every {@code /api/**} request must carry a valid bearer token
 * whose issuer and audience are validated by Spring Security from {@code
 * spring.security.oauth2.resourceserver.jwt.*}. Health and metrics are open (they live behind the
 * cluster boundary); everything else is denied by default — new endpoints opt in under /api.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(BearerTokenAuthenticationFilter.class)
@EnableMethodSecurity
public class ResourceServerSecurityAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public TenantJwtAuthenticationConverter tenantJwtAuthenticationConverter() {
    return new TenantJwtAuthenticationConverter();
  }

  @Bean
  @ConditionalOnMissingBean(SecurityFilterChain.class)
  public SecurityFilterChain apiSecurityFilterChain(
      HttpSecurity http, TenantJwtAuthenticationConverter converter) throws Exception {
    http.csrf(csrf -> csrf.disable())
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            requests ->
                requests
                    .requestMatchers(
                        "/actuator/health",
                        "/actuator/health/**",
                        "/actuator/info",
                        "/actuator/prometheus")
                    .permitAll()
                    .requestMatchers("/api/**")
                    .authenticated()
                    .anyRequest()
                    .denyAll())
        .oauth2ResourceServer(
            oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(converter)))
        .addFilterAfter(new MdcContextFilter(), BearerTokenAuthenticationFilter.class)
        .httpBasic(basic -> basic.disable())
        .formLogin(Customizer.withDefaults())
        .formLogin(form -> form.disable());
    return http.build();
  }
}
