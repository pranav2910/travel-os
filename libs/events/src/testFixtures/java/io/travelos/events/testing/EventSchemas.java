package io.travelos.events.testing;

import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Validates event JSON against contracts/events. Shared test fixture: a service that publishes
 * {@code travel.trip.created} asserts {@code EventSchemas.violations(json)} is empty and is thereby
 * held to the same contract every consumer reads.
 */
public final class EventSchemas {

  public static final String BASE = "https://contracts.travelos.io/events/";

  private static final SchemaRegistry REGISTRY =
      SchemaRegistry.withDefaultDialect(
          SpecificationVersion.DRAFT_2020_12,
          builder ->
              builder.schemaIdResolvers(resolvers -> resolvers.mapPrefix(BASE, "classpath:")));

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private EventSchemas() {}

  /** Every schema file whose $defs declare event types, keyed by event type. */
  public static Map<String, String> schemaFileByEventType() {
    Map<String, String> result = new LinkedHashMap<>();
    for (String file : schemaFiles()) {
      JsonNode defs = JSON.readTree(resource(file)).get("$defs");
      if (defs == null) {
        continue;
      }
      defs.propertyNames().stream()
          .filter(name -> name.startsWith("travel."))
          .forEach(type -> result.put(type, file));
    }
    return result;
  }

  /** Envelope violations + data violations for the event type named inside the document. */
  public static List<String> violations(String eventJson) {
    List<String> violations =
        new ArrayList<>(errors(schema(BASE + "event-envelope.schema.json"), eventJson));
    JsonNode document = JSON.readTree(eventJson);
    JsonNode type = document.get("eventType");
    if (type == null || violations.stream().anyMatch(v -> v.contains("/eventType"))) {
      return violations;
    }
    String file = schemaFileByEventType().get(type.asString());
    if (file == null) {
      violations.add("/eventType: no data schema declares " + type.asString());
      return violations;
    }
    Schema data = schema(BASE + file + "#/$defs/" + type.asString());
    for (String error : errors(data, JSON.writeValueAsString(document.get("data")))) {
      violations.add("/data" + error);
    }
    return violations;
  }

  /** Topic registry as declared in topics.yaml: topic name to event types. */
  public static Map<String, List<String>> registeredEvents() {
    JsonNode registry = YAMLMapper.builder().build().readTree(resource("topics.yaml"));
    Map<String, List<String>> result = new LinkedHashMap<>();
    for (JsonNode topic : registry.get("topics")) {
      List<String> events = new ArrayList<>();
      topic.get("events").forEach(event -> events.add(event.asString()));
      result.put(topic.get("name").asString(), events);
    }
    return result;
  }

  public static String example(String eventType) {
    return resource("examples/" + eventType + ".json");
  }

  private static List<String> schemaFiles() {
    return List.of(
        "trip-events.schema.json",
        "order-events.schema.json",
        "policy-events.schema.json",
        "optimization-events.schema.json",
        "approval-events.schema.json",
        "intent-events.schema.json",
        "disruption-events.schema.json",
        "demand-events.schema.json",
        "learning-events.schema.json",
        "finance-events.schema.json",
        "assistance-events.schema.json");
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

  public static String resource(String name) {
    try (InputStream in = EventSchemas.class.getResourceAsStream("/" + name)) {
      if (in == null) {
        throw new IllegalArgumentException(name + " is not on the classpath");
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
