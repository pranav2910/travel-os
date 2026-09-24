package io.travelos.assistance;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(AssistanceProperties.class)
public class AssistanceApplication {
  public static void main(String[] args) {
    SpringApplication.run(AssistanceApplication.class, args);
  }
}
