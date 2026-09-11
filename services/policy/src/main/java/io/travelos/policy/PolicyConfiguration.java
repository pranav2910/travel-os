package io.travelos.policy;

import io.travelos.policy.engine.PolicyEngine;
import io.travelos.policy.engine.RouteClassifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class PolicyConfiguration {

  @Bean
  PolicyEngine policyEngine() {
    return new PolicyEngine();
  }

  @Bean
  RouteClassifier routeClassifier() {
    return RouteClassifier.fromClasspath();
  }
}
