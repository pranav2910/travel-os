package io.travelos.events;

import java.util.List;

/**
 * The Kafka topic registry, mirrored from {@code contracts/events/topics.yaml}. A test keeps the
 * two (and the local create-topics script) identical. Topic = first two segments of the event type.
 */
public final class Topics {

  public static final String INTENT = "travel.intent";
  public static final String TRIP = "travel.trip";
  public static final String SEARCH = "travel.search";
  public static final String POLICY = "travel.policy";
  public static final String OPTIMIZATION = "travel.optimization";
  public static final String APPROVAL = "travel.approval";
  public static final String ORDER = "travel.order";
  public static final String DISRUPTION = "travel.disruption";
  public static final String EXPENSE = "travel.expense";
  public static final String DEMAND = "travel.demand";
  public static final String LEARNING = "travel.learning";
  public static final String AGENT = "travel.agent";
  public static final String AUDIT = "travel.audit";

  public static final List<String> ALL =
      List.of(
          INTENT,
          TRIP,
          SEARCH,
          POLICY,
          OPTIMIZATION,
          APPROVAL,
          ORDER,
          DISRUPTION,
          EXPENSE,
          DEMAND,
          LEARNING,
          AGENT,
          AUDIT);

  private Topics() {}

  /** {@code travel.order.confirmed} -> {@code travel.order}. Throws for unregistered topics. */
  public static String topicFor(String eventType) {
    int first = eventType.indexOf('.');
    int second = first < 0 ? -1 : eventType.indexOf('.', first + 1);
    if (second < 0) {
      throw new IllegalArgumentException(
          "event type must have at least three segments: " + eventType);
    }
    String topic = eventType.substring(0, second);
    if (!ALL.contains(topic)) {
      throw new IllegalArgumentException(
          "event type " + eventType + " maps to unregistered topic " + topic);
    }
    return topic;
  }
}
