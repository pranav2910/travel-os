package io.travelos.events;

import static org.assertj.core.api.Assertions.assertThat;

import io.travelos.events.testing.EventSchemas;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Three places know the topic list: contracts/events/topics.yaml (the source of truth), {@link
 * Topics} (what producers use) and platform/local/kafka/create-topics.sh (what the local broker
 * gets). This test fails the build the moment they disagree.
 */
class TopicsRegistryTest {

  @Test
  void javaConstantsMatchTheRegistry() throws IOException {
    assertThat(Topics.ALL).containsExactlyElementsOf(registryTopicNames());
  }

  @Test
  void localCreateScriptMatchesTheRegistry() throws IOException {
    String script = resource("create-topics.sh");
    Matcher matcher = Pattern.compile("TOPICS=\\(([^)]*)\\)").matcher(script);
    assertThat(matcher.find()).as("TOPICS=(...) array in create-topics.sh").isTrue();
    List<String> scripted = List.of(matcher.group(1).trim().split("\\s+"));
    assertThat(scripted).containsExactlyElementsOf(registryTopicNames());
  }

  @Test
  void everyRegisteredEventTypeMapsBackToItsTopic() throws IOException {
    JsonNode registry = registry();
    for (JsonNode topic : registry.get("topics")) {
      String name = topic.get("name").asString();
      for (JsonNode event : topic.get("events")) {
        assertThat(Topics.topicFor(event.asString())).isEqualTo(name);
      }
    }
  }

  private static List<String> registryTopicNames() throws IOException {
    List<String> names = new ArrayList<>();
    for (JsonNode topic : registry().get("topics")) {
      names.add(topic.get("name").asString());
    }
    return names;
  }

  private static JsonNode registry() {
    return YAMLMapper.builder().build().readTree(EventSchemas.resource("topics.yaml"));
  }

  private static String resource(String name) throws IOException {
    try (InputStream in = TopicsRegistryTest.class.getResourceAsStream("/" + name)) {
      assertThat(in).as(name + " on test classpath").isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
