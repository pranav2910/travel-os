package io.travelos.spring.outbox;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapSetter;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** W3C traceparent in, W3C traceparent out. The outbox row is the carrier across the async gap. */
final class TraceContexts {

  static final String TRACEPARENT = "traceparent";

  private static final TextMapSetter<Map<String, String>> SETTER =
      (carrier, key, value) -> {
        if (carrier != null) {
          carrier.put(key, value);
        }
      };

  private static final TextMapGetter<Map<String, String>> GETTER =
      new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Map<String, String> carrier) {
          return carrier.keySet();
        }

        @Override
        public @Nullable String get(@Nullable Map<String, String> carrier, String key) {
          return carrier == null ? null : carrier.get(key);
        }
      };

  private TraceContexts() {}

  /** The current trace as a traceparent header value, or null when nothing is being traced. */
  static @Nullable String current(@Nullable OpenTelemetry otel) {
    if (otel == null) {
      return null;
    }
    Map<String, String> carrier = new HashMap<>();
    otel.getPropagators().getTextMapPropagator().inject(Context.current(), carrier, SETTER);
    return carrier.get(TRACEPARENT);
  }

  static Context parent(OpenTelemetry otel, @Nullable String traceParent) {
    if (traceParent == null || traceParent.isBlank()) {
      return Context.root();
    }
    return otel.getPropagators()
        .getTextMapPropagator()
        .extract(Context.root(), Map.of(TRACEPARENT, traceParent), GETTER);
  }

  /** The given context as headers to put on the Kafka record. */
  static Map<String, String> headers(OpenTelemetry otel, Context context) {
    Map<String, String> carrier = new HashMap<>();
    otel.getPropagators().getTextMapPropagator().inject(context, carrier, SETTER);
    return carrier;
  }
}
