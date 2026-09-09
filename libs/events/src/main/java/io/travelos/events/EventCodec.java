package io.travelos.events;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * JSON on the wire. Strict on read: an unknown top-level field is a contract violation, not
 * something to shrug at, because the envelope is {@code additionalProperties: false}.
 */
public final class EventCodec {

  private final ObjectMapper mapper;

  public EventCodec() {
    this.mapper =
        JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
  }

  public String toJson(EventEnvelope envelope) {
    return mapper.writeValueAsString(envelope);
  }

  public byte[] toBytes(EventEnvelope envelope) {
    return mapper.writeValueAsBytes(envelope);
  }

  public EventEnvelope fromJson(String json) {
    return mapper.readValue(json, EventEnvelope.class);
  }

  public EventEnvelope fromBytes(byte[] json) {
    return mapper.readValue(json, EventEnvelope.class);
  }
}
