package io.travelos.events;

import static org.assertj.core.api.Assertions.assertThat;

import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * The contract test for contracts/events. For every event type declared in a *-events.schema.json:
 *
 * <ul>
 *   <li>an example exists under examples/&lt;eventType&gt;.json,
 *   <li>the example is a valid envelope (format assertions on, so dates are real dates),
 *   <li>the example's data validates against that event type's $def,
 *   <li>the example round-trips through {@link EventCodec} unchanged,
 *   <li>the event type is registered under its topic in topics.yaml.
 * </ul>
 *
 * Producers and consumers in every language test against the same files, so this is what "the
 * contract" means in practice.
 */
class EventContractsTest {

  private static final String BASE = "https://contracts.travelos.io/events/";
  private static final List<String> SCHEMA_FILES =
      List.of(
          "trip-events.schema.json",
          "order-events.schema.json",
          "policy-events.schema.json",
          "optimization-events.schema.json",
          "approval-events.schema.json");

  private static final SchemaRegistry REGISTRY =
      SchemaRegistry.withDefaultDialect(
          SpecificationVersion.DRAFT_2020_12,
          builder ->
              builder.schemaIdResolvers(resolvers -> resolvers.mapPrefix(BASE, "classpath:")));

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private final EventCodec codec = new EventCodec();

  @TestFactory
  Stream<DynamicTest> everyDeclaredEventTypeHasAValidExample() throws IOException {
    List<DynamicTest> tests = new ArrayList<>();
    for (String file : SCHEMA_FILES) {
      JsonNode defs = JSON.readTree(resource(file)).get("$defs");
      assertThat(defs).as(file + " has $defs").isNotNull();
      defs.propertyNames().stream()
          .filter(name -> name.startsWith("travel."))
          .forEach(
              eventType ->
                  tests.add(DynamicTest.dynamicTest(eventType, () -> check(file, eventType))));
    }
    assertThat(tests).isNotEmpty();
    return tests.stream();
  }

  @Test
  void everyExampleBelongsToADeclaredEventType() throws IOException {
    List<String> declared = new ArrayList<>();
    for (String file : SCHEMA_FILES) {
      JSON.readTree(resource(file)).get("$defs").propertyNames().stream()
          .filter(name -> name.startsWith("travel."))
          .forEach(declared::add);
    }
    JsonNode registry = YAMLMapper.builder().build().readTree(resource("topics.yaml"));
    for (JsonNode topic : registry.get("topics")) {
      for (JsonNode event : topic.get("events")) {
        String type = event.asString();
        boolean hasExample =
            EventContractsTest.class.getResource("/examples/" + type + ".json") != null;
        if (hasExample) {
          assertThat(declared).as("example without a schema $def: " + type).contains(type);
        }
      }
    }
  }

  private void check(String schemaFile, String eventType) throws IOException {
    String example = resource("examples/" + eventType + ".json");

    // 1. Envelope.
    Schema envelope = schema(BASE + "event-envelope.schema.json");
    assertThat(errors(envelope, example)).as("envelope of " + eventType).isEmpty();

    // 2. Data payload against the event type's own $def.
    JsonNode document = JSON.readTree(example);
    assertThat(document.get("eventType").asString()).isEqualTo(eventType);
    Schema data = schema(BASE + schemaFile + "#/$defs/" + eventType);
    assertThat(errors(data, JSON.writeValueAsString(document.get("data"))))
        .as("data of " + eventType)
        .isEmpty();

    // 3. Java model agrees with the JSON and re-emits it losslessly.
    EventEnvelope decoded = codec.fromJson(example);
    assertThat(decoded.eventType()).isEqualTo(eventType);
    assertThat(decoded.topic()).isEqualTo(Topics.topicFor(eventType));
    assertThat(JSON.readTree(codec.toJson(decoded))).isEqualTo(document);

    // 4. Registered under its topic.
    assertThat(registeredEvents().get(Topics.topicFor(eventType)))
        .as(eventType + " listed in topics.yaml")
        .contains(eventType);
  }

  private static Schema schema(String location) {
    Schema schema = REGISTRY.getSchema(SchemaLocation.of(location));
    schema.initializeValidators();
    return schema;
  }

  private static List<String> errors(Schema schema, String json) {
    List<Error> errors =
        schema.validate(
            json,
            InputFormat.JSON,
            context -> context.executionConfig(config -> config.formatAssertionsEnabled(true)));
    return errors.stream().map(Error::toString).toList();
  }

  private static Map<String, List<String>> registeredEvents() throws IOException {
    JsonNode registry = YAMLMapper.builder().build().readTree(resource("topics.yaml"));
    Map<String, List<String>> result = new java.util.LinkedHashMap<>();
    for (JsonNode topic : registry.get("topics")) {
      List<String> events = new ArrayList<>();
      topic.get("events").forEach(event -> events.add(event.asString()));
      result.put(topic.get("name").asString(), events);
    }
    return result;
  }

  private static String resource(String name) throws IOException {
    try (InputStream in = EventContractsTest.class.getResourceAsStream("/" + name)) {
      assertThat(in).as(name + " on test classpath").isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
