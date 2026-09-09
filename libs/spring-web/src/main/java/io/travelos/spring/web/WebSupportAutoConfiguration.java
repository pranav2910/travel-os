package io.travelos.spring.web;

import io.travelos.spring.web.error.ApiExceptionHandler;
import io.travelos.spring.web.idempotency.IdempotencyKeyHeaderArgumentResolver;
import java.util.List;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Import(ApiExceptionHandler.class)
public class WebSupportAutoConfiguration {

  @Bean
  public WebMvcConfigurer travelosWebMvcConfigurer() {
    return new WebMvcConfigurer() {
      @Override
      public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new IdempotencyKeyHeaderArgumentResolver());
      }
    };
  }
}
