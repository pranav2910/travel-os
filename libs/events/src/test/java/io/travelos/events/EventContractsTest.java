package io.travelos.events;

import static org.assertj.core.api.Assertions.assertThat;

import io.travelos.events.testing.EventSchemas;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

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

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private final EventCodec codec = new EventCodec();

  @TestFactory
  Stream<DynamicTest> everyDeclaredEventTypeHasAValidExample() {
    Map<String, String> declared = EventSchemas.schemaFileByEventType();
    assertThat(declared).isNotEmpty();
    List<DynamicTest> tests = new ArrayList<>();
    declared.keySet().forEach(type -> tests.add(DynamicTest.dynamicTest(type, () -> check(type))));
    return tests.stream();
  }

  @Test
  void everyExampleBelongsToADeclaredEventType() {
    Map<String, String> declared = EventSchemas.schemaFileByEventType();
    EventSchemas.registeredEvents()
        .values()
        .forEach(
            events ->
                events.forEach(
                    type -> {
                      boolean hasExample =
                          EventContractsTest.class.getResource("/examples/" + type + ".json")
                              != null;
                      if (hasExample) {
                        assertThat(declared)
                            .as("example without a schema $def: " + type)
                            .containsKey(type);
                      }
                    }));
  }

  private void check(String eventType) {
    String example = EventSchemas.example(eventType);
    JsonNode document = JSON.readTree(example);

    assertThat(document.get("eventType").asString()).isEqualTo(eventType);
    assertThat(EventSchemas.violations(example))
        .as("contract violations for " + eventType)
        .isEmpty();

    EventEnvelope decoded = codec.fromJson(example);
    assertThat(decoded.eventType()).isEqualTo(eventType);
    assertThat(decoded.topic()).isEqualTo(Topics.topicFor(eventType));
    assertThat(JSON.readTree(codec.toJson(decoded))).isEqualTo(document);

    assertThat(EventSchemas.registeredEvents().get(Topics.topicFor(eventType)))
        .as(eventType + " listed in topics.yaml")
        .contains(eventType);
  }
}
