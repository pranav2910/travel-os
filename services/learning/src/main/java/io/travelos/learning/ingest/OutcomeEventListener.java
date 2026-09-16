package io.travelos.learning.ingest;

import io.travelos.events.EventCodec;
import io.travelos.events.EventEnvelope;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * The durable consumer: trips, orders, disruptions and optimizer decisions, one consumer group, the
 * offset committed only after the outcome row is durable. A record that cannot be parsed is logged
 * and skipped: it cannot be a valid event, and blocking the partition would stop every other
 * outcome.
 */
@Component
public class OutcomeEventListener {
  private static final Logger log = LoggerFactory.getLogger(OutcomeEventListener.class);
  private final OutcomeIngestService ingest;
  private final EventCodec codec = new EventCodec();

  public OutcomeEventListener(OutcomeIngestService ingest) {
    this.ingest = ingest;
  }

  @KafkaListener(
      id = "learning-outcomes",
      topics = {"travel.trip", "travel.order", "travel.disruption", "travel.optimization"},
      groupId = "${spring.kafka.consumer.group-id}")
  public void onRecord(ConsumerRecord<String, String> record) {
    EventEnvelope event;
    try {
      event = codec.fromJson(record.value());
    } catch (RuntimeException e) {
      log.error("unreadable event on {}, skipping: {}", record.topic(), e.getMessage());
      return;
    }
    OutcomeIngestService.Result result = ingest.ingest(event);
    if (result != OutcomeIngestService.Result.IGNORED) {
      log.info("{} {} -> {}", event.eventType(), event.eventId(), result);
    }
  }
}
