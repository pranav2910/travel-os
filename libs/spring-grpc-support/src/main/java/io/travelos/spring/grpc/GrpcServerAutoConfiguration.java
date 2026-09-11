package io.travelos.spring.grpc;

import io.grpc.BindableService;
import io.grpc.Server;
import io.grpc.ServerInterceptor;
import io.grpc.protobuf.services.HealthStatusManager;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@ConditionalOnClass(Server.class)
@ConditionalOnProperty(
    prefix = "travelos.grpc.server",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
@EnableConfigurationProperties(GrpcServerProperties.class)
public class GrpcServerAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public HealthStatusManager grpcHealthStatusManager() {
    return new HealthStatusManager();
  }

  @Bean
  public MdcServerInterceptor grpcMdcServerInterceptor() {
    return new MdcServerInterceptor();
  }

  @Bean
  @ConditionalOnMissingBean
  public ExceptionMappingInterceptor grpcExceptionMappingInterceptor() {
    return new ExceptionMappingInterceptor();
  }

  @Bean
  public GrpcServerLifecycle grpcServerLifecycle(
      GrpcServerProperties properties,
      ObjectProvider<BindableService> services,
      ObjectProvider<ServerInterceptor> interceptors,
      HealthStatusManager health) {
    // Interceptors run in registration order for outbound and reverse for inbound; MDC first so
    // the exception mapper logs with context.
    List<ServerInterceptor> ordered =
        interceptors.orderedStream().sorted((a, b) -> rank(a) - rank(b)).toList();
    return new GrpcServerLifecycle(properties, services.orderedStream().toList(), ordered, health);
  }

  private static int rank(ServerInterceptor interceptor) {
    if (interceptor instanceof ExceptionMappingInterceptor) {
      return 0;
    }
    if (interceptor instanceof MdcServerInterceptor) {
      return 1;
    }
    return 2;
  }
}
