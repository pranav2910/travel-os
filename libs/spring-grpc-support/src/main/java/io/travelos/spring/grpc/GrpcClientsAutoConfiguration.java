package io.travelos.spring.grpc;

import io.grpc.ManagedChannel;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@ConditionalOnClass(ManagedChannel.class)
@EnableConfigurationProperties(GrpcClientProperties.class)
public class GrpcClientsAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public GrpcChannels grpcChannels(GrpcClientProperties properties) {
    return new GrpcChannels(properties);
  }
}
