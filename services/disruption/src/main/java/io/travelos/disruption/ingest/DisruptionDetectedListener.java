package io.travelos.disruption.ingest;

import io.travelos.events.EventCodec;
import io.travelos.events.EventEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Listens to travel.disruption for what the supplier gateway detected. Everything else on the topic
 * is our own output. A record that cannot even be parsed is logged and skipped: it cannot be a
 * valid detected event, and blocking the partition would stop every other recovery.
 */
@Component
public class DisruptionDetectedListener {

  private static final Logger log = LoggerFactory.getLogger(DisruptionDetectedListener.class);
  private final DisruptionIngestService ingest;
  private final EventCodec codec = new EventCodec();

  public DisruptionDetectedListener(DisruptionIngestService ingest) {
    this.ingest = ingest;
  }

  @KafkaListener(topics = "travel.disruption", groupId = "${spring.kafka.consumer.group-id}")
  public void onDisruptionEvent(String payload) {
    EventEnvelope event;
    try {
      event = codec.fromJson(payload);
    } catch (RuntimeException e) {
      log.error("unreadable event on travel.disruption, skipping: {}", e.getMessage());
      return;
    }
    if (!"travel.disruption.detected".equals(event.eventType())) {
      return;
    }
    DisruptionIngestService.Outcome outcome = ingest.ingest(event);
    log.info("{} -> {}", event.eventId(), outcome);
  }
}
