package io.travelos.audit.ingest;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Every travel.* topic, one consumer group. Offsets commit per record after the row is durable. */
@Component
public class AuditEventListener {

  private final AuditIngestService ingest;

  public AuditEventListener(AuditIngestService ingest) {
    this.ingest = ingest;
  }

  @KafkaListener(
      id = "audit",
      topicPattern = "travel\\..*",
      groupId = "${spring.kafka.consumer.group-id}")
  public void onRecord(ConsumerRecord<String, String> record) {
    ingest.ingest(record.topic(), record.partition(), record.offset(), record.value());
  }
}
