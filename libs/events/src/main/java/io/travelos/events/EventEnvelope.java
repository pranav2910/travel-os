package io.travelos.events;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.travelos.common.ids.IdPrefix;
import io.travelos.common.ids.Ids;
import io.travelos.common.tenant.TenantId;
import java.time.Clock;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The one event shape on every topic — see {@code contracts/events/event-envelope.schema.json}.
 * Validation here mirrors the schema so a malformed envelope cannot be constructed, let alone
 * published.
 *
 * @param eventId {@code evt_<ULID>}
 * @param eventType dotted lowercase, e.g. {@code travel.order.confirmed}
 * @param eventVersion schema version of {@code data}, starting at 1
 * @param occurredAt when the fact became true (not when it was published)
 * @param tenantId owning tenant
 * @param correlationId the business thread, normally the trip id
 * @param causationId the command that caused this event, if known
 * @param producer service name
 * @param data the event-type-specific payload, validated by the per-topic schema
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EventEnvelope(
    String eventId,
    String eventType,
    int eventVersion,
    Instant occurredAt,
    String tenantId,
    String correlationId,
    @Nullable String causationId,
    String producer,
    Map<String, Object> data) {

  private static final Pattern EVENT_TYPE =
      Pattern.compile("^travel\\.[a-z]+\\.[a-z_]+(\\.[a-z_]+)*$");
  private static final Pattern PRODUCER = Pattern.compile("^[a-z][a-z0-9-]*$");

  public EventEnvelope {
    Ids.require(IdPrefix.EVENT, eventId);
    Objects.requireNonNull(eventType, "eventType");
    if (!EVENT_TYPE.matcher(eventType).matches()) {
      throw new IllegalArgumentException("malformed event type: " + eventType);
    }
    // Registered topic or nothing.
    Topics.topicFor(eventType);
    if (eventVersion < 1) {
      throw new IllegalArgumentException("eventVersion must be >= 1: " + eventVersion);
    }
    Objects.requireNonNull(occurredAt, "occurredAt");
    TenantId.of(tenantId);
    if (correlationId == null || correlationId.isBlank() || correlationId.length() > 128) {
      throw new IllegalArgumentException("correlationId must be 1-128 chars: " + correlationId);
    }
    if (causationId != null && (causationId.isBlank() || causationId.length() > 128)) {
      throw new IllegalArgumentException("causationId must be 1-128 chars when present");
    }
    Objects.requireNonNull(producer, "producer");
    if (!PRODUCER.matcher(producer).matches()) {
      throw new IllegalArgumentException("malformed producer: " + producer);
    }
    Objects.requireNonNull(data, "data");
    data = Collections.unmodifiableMap(new LinkedHashMap<>(data));
  }

  public static EventEnvelope create(
      String eventType,
      int eventVersion,
      TenantId tenantId,
      String correlationId,
      @Nullable String causationId,
      String producer,
      Map<String, Object> data,
      Clock clock) {
    return new EventEnvelope(
        Ids.newId(IdPrefix.EVENT),
        eventType,
        eventVersion,
        clock.instant(),
        tenantId.value(),
        correlationId,
        causationId,
        producer,
        data);
  }

  /** The Kafka topic this event belongs on. */
  public String topic() {
    return Topics.topicFor(eventType);
  }

  /** The Kafka record key: all events of one correlation land on one partition, in order. */
  public String partitionKey() {
    return correlationId;
  }
}
