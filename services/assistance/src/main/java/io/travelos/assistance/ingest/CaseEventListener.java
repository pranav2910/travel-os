package io.travelos.assistance.ingest;

import io.travelos.events.EventCodec;
import io.travelos.events.EventEnvelope;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * The durable consumer: orders, trips, disruptions and finance, one consumer group, the offset
 * committed only after the case row is durable. A record that cannot be parsed is logged and
 * skipped: it cannot be a valid event, and blocking the partition would stop every other case.
 */
@Component
public class CaseEventListener {
  private static final Logger log = LoggerFactory.getLogger(CaseEventListener.class);
  private final CaseIngestService ingest;
  private final EventCodec codec = new EventCodec();

  public CaseEventListener(CaseIngestService ingest) {
    this.ingest = ingest;
  }

  @KafkaListener(
      id = "assistance-cases",
      topics = {
        "travel.trip",
        "travel.order",
        "travel.disruption",
        "travel.finance",
        "travel.approval"
      },
      groupId = "${spring.kafka.consumer.group-id}")
  public void onRecord(ConsumerRecord<String, String> record) {
    EventEnvelope event;
    try {
      event = codec.fromJson(record.value());
    } catch (RuntimeException e) {
      log.error("unreadable event on {}, skipping: {}", record.topic(), e.getMessage());
      return;
    }
    CaseIngestService.Result result = ingest.ingest(event);
    if (result != CaseIngestService.Result.IGNORED) {
      log.info("{} {} -> {}", event.eventType(), event.eventId(), result);
    }
  }
}
