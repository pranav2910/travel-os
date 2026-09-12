package io.travelos.spring.grpc;

import io.grpc.ManagedChannel;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.ObjectProvider;
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
  public GrpcChannels grpcChannels(
      GrpcClientProperties properties, ObjectProvider<ObservationRegistry> observations) {
    return new GrpcChannels(properties, observations.getIfAvailable());
  }
}
