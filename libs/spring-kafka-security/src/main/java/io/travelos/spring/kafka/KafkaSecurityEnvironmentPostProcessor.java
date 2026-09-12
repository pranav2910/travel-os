package io.travelos.spring.kafka;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Turns one switch into the Kafka client security configuration for every client Spring Boot builds
 * (producers, consumers, admin):
 *
 * <pre>
 *   KAFKA_AUTH=none      PLAINTEXT — the local platform, the kind stand-in broker      (default)
 *   KAFKA_AUTH=msk-iam   SASL_SSL + AWS_MSK_IAM — Amazon MSK; the pod's IAM role (IRSA) signs
 *   KAFKA_AUTH=tls       SSL only — a TLS listener without SASL (e.g. a private Kafka with mTLS off)
 * </pre>
 *
 * Trust: MSK brokers present Amazon Trust Services certificates, which the JDK trusts out of the
 * box. A private CA is supported through {@code KAFKA_SSL_TRUSTSTORE_LOCATION} (+ {@code
 * _PASSWORD}, {@code _TYPE}). The properties are added ahead of every other source, so a service
 * cannot accidentally ship a YAML that downgrades the protocol.
 */
public final class KafkaSecurityEnvironmentPostProcessor
    implements EnvironmentPostProcessor, Ordered {

  static final String SOURCE = "travelosKafkaSecurity";
  static final String AUTH = "travelos.kafka.auth";
  static final String AUTH_ENV = "KAFKA_AUTH";
  static final String TRUSTSTORE_LOCATION_ENV = "KAFKA_SSL_TRUSTSTORE_LOCATION";
  static final String TRUSTSTORE_PASSWORD_ENV = "KAFKA_SSL_TRUSTSTORE_PASSWORD";
  static final String TRUSTSTORE_TYPE_ENV = "KAFKA_SSL_TRUSTSTORE_TYPE";

  static final String IAM_LOGIN_MODULE = "software.amazon.msk.auth.iam.IAMLoginModule";
  static final String IAM_CALLBACK_HANDLER =
      "software.amazon.msk.auth.iam.IAMClientCallbackHandler";

  @Override
  public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication application) {
    Map<String, Object> props = properties(env);
    if (!props.isEmpty()) {
      env.getPropertySources().addFirst(new MapPropertySource(SOURCE, props));
    }
  }

  static Map<String, Object> properties(ConfigurableEnvironment env) {
    String auth = firstNonBlank(env.getProperty(AUTH), env.getProperty(AUTH_ENV), "none");
    Map<String, Object> props = new LinkedHashMap<>();
    switch (auth.trim().toLowerCase(Locale.ROOT)) {
      case "none", "plaintext" -> {
        /* the broker's default listener; nothing to add */
      }
      case "msk-iam" -> {
        props.put("spring.kafka.security.protocol", "SASL_SSL");
        props.put("spring.kafka.properties.sasl.mechanism", "AWS_MSK_IAM");
        props.put("spring.kafka.properties.sasl.jaas.config", IAM_LOGIN_MODULE + " required;");
        props.put(
            "spring.kafka.properties.sasl.client.callback.handler.class", IAM_CALLBACK_HANDLER);
      }
      case "tls" -> props.put("spring.kafka.security.protocol", "SSL");
      default ->
          throw new IllegalStateException(
              "unknown " + AUTH + " '" + auth + "': expected none, msk-iam or tls");
    }
    String truststore = env.getProperty(TRUSTSTORE_LOCATION_ENV);
    if (truststore != null && !truststore.isBlank()) {
      String location = truststore.contains(":") ? truststore : "file:" + truststore;
      props.put("spring.kafka.ssl.trust-store-location", location);
      String password = env.getProperty(TRUSTSTORE_PASSWORD_ENV);
      if (password != null && !password.isEmpty()) {
        props.put("spring.kafka.ssl.trust-store-password", password);
      }
      String type = env.getProperty(TRUSTSTORE_TYPE_ENV);
      if (type != null && !type.isBlank()) {
        props.put("spring.kafka.ssl.trust-store-type", type);
      }
    }
    return props;
  }

  private static String firstNonBlank(@Nullable String a, @Nullable String b, String fallback) {
    if (a != null && !a.isBlank()) {
      return a;
    }
    if (b != null && !b.isBlank()) {
      return b;
    }
    return fallback;
  }

  @Override
  public int getOrder() {
    return Ordered.LOWEST_PRECEDENCE; // after config files are loaded, so addFirst really is first
  }
}
