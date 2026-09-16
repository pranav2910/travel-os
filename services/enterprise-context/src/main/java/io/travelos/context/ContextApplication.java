package io.travelos.context;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(ContextProperties.class)
public class ContextApplication {
  public static void main(String[] args) {
    SpringApplication.run(ContextApplication.class, args);
  }
}
