package io.travelos.order;

import io.travelos.order.supplier.SupplierClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SupplierClient.RetryProperties.class)
class OrderConfiguration {}
