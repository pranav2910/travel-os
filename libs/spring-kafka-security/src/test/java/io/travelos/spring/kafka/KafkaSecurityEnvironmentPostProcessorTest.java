package io.travelos.spring.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

class KafkaSecurityEnvironmentPostProcessorTest {

  private final KafkaSecurityEnvironmentPostProcessor processor =
      new KafkaSecurityEnvironmentPostProcessor();

  @Test
  void defaultIsPlaintextAndAddsNothing() {
    MockEnvironment env = new MockEnvironment();
    processor.postProcessEnvironment(env, new SpringApplication());
    assertThat(env.getPropertySources().contains(KafkaSecurityEnvironmentPostProcessor.SOURCE))
        .isFalse();
    assertThat(env.getProperty("spring.kafka.security.protocol")).isNull();
  }

  @Test
  void mskIamSwitchesEveryClientToSaslSslWithTheIamLoginModule() {
    MockEnvironment env = new MockEnvironment().withProperty("KAFKA_AUTH", "msk-iam");
    processor.postProcessEnvironment(env, new SpringApplication());
    assertThat(env.getProperty("spring.kafka.security.protocol")).isEqualTo("SASL_SSL");
    assertThat(env.getProperty("spring.kafka.properties.sasl.mechanism")).isEqualTo("AWS_MSK_IAM");
    assertThat(env.getProperty("spring.kafka.properties.sasl.jaas.config"))
        .isEqualTo("software.amazon.msk.auth.iam.IAMLoginModule required;");
    assertThat(env.getProperty("spring.kafka.properties.sasl.client.callback.handler.class"))
        .isEqualTo("software.amazon.msk.auth.iam.IAMClientCallbackHandler");
  }

  @Test
  void theSwitchWinsOverAnythingAServiceYamlSays() {
    MockEnvironment env =
        new MockEnvironment()
            .withProperty("travelos.kafka.auth", "msk-iam")
            .withProperty("spring.kafka.security.protocol", "PLAINTEXT");
    processor.postProcessEnvironment(env, new SpringApplication());
    assertThat(env.getProperty("spring.kafka.security.protocol")).isEqualTo("SASL_SSL");
  }

  @Test
  void privateCaTruststoreIsWiredWhenGiven() {
    MockEnvironment env =
        new MockEnvironment()
            .withProperty("KAFKA_AUTH", "tls")
            .withProperty("KAFKA_SSL_TRUSTSTORE_LOCATION", "/etc/kafka/ca.p12")
            .withProperty("KAFKA_SSL_TRUSTSTORE_PASSWORD", "s3cret")
            .withProperty("KAFKA_SSL_TRUSTSTORE_TYPE", "PKCS12");
    processor.postProcessEnvironment(env, new SpringApplication());
    assertThat(env.getProperty("spring.kafka.security.protocol")).isEqualTo("SSL");
    assertThat(env.getProperty("spring.kafka.ssl.trust-store-location"))
        .isEqualTo("file:/etc/kafka/ca.p12");
    assertThat(env.getProperty("spring.kafka.ssl.trust-store-password")).isEqualTo("s3cret");
    assertThat(env.getProperty("spring.kafka.ssl.trust-store-type")).isEqualTo("PKCS12");
  }

  @Test
  void unknownModeFailsFastAtStartup() {
    MockEnvironment env = new MockEnvironment().withProperty("KAFKA_AUTH", "kerberos");
    assertThatThrownBy(() -> processor.postProcessEnvironment(env, new SpringApplication()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("kerberos");
  }

  /**
   * The strings are only useful if the classes behind them exist and the Kafka client accepts the
   * combination: build a real producer with the MSK IAM configuration (no broker is contacted).
   */
  @Test
  void aRealKafkaClientAcceptsTheMskIamConfiguration() throws Exception {
    assertThat(Class.forName(KafkaSecurityEnvironmentPostProcessor.IAM_CALLBACK_HANDLER))
        .isNotNull();
    assertThat(Class.forName(KafkaSecurityEnvironmentPostProcessor.IAM_LOGIN_MODULE)).isNotNull();
    MockEnvironment env = new MockEnvironment().withProperty("KAFKA_AUTH", "msk-iam");
    Map<String, Object> props = KafkaSecurityEnvironmentPostProcessor.properties(env);
    Map<String, Object> config =
        Map.of(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
            "localhost:9098",
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
            StringSerializer.class,
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
            StringSerializer.class,
            ProducerConfig.MAX_BLOCK_MS_CONFIG,
            "1",
            "security.protocol",
            props.get("spring.kafka.security.protocol"),
            "sasl.mechanism",
            props.get("spring.kafka.properties.sasl.mechanism"),
            "sasl.jaas.config",
            props.get("spring.kafka.properties.sasl.jaas.config"),
            "sasl.client.callback.handler.class",
            props.get("spring.kafka.properties.sasl.client.callback.handler.class"));
    try (KafkaProducer<String, String> producer = new KafkaProducer<>(config)) {
      assertThat(producer).isNotNull();
    }
  }
}
