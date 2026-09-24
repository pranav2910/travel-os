package io.travelos.audit.ingest;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.trace.Span;
import io.travelos.audit.store.AuditRepository;
import io.travelos.common.tenant.TenantId;
import io.travelos.events.EventCodec;
import io.travelos.events.EventEnvelope;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * One Kafka record in, one durable row out. Redeliveries are absorbed by the event id; unreadable
 * payloads go to quarantine so a single bad message never blocks a partition.
 */
@Service
public class AuditIngestService {

  private static final Logger log = LoggerFactory.getLogger(AuditIngestService.class);

  private final AuditRepository repository;
  private final EventCodec codec;
  private final Clock clock;
  private final Counter stored;
  private final Counter duplicates;
  private final Counter quarantined;

  private final io.travelos.audit.report.ReportFacts facts;

  public AuditIngestService(
      AuditRepository repository,
      EventCodec codec,
      Clock clock,
      MeterRegistry meters,
      io.travelos.audit.report.ReportFacts facts) {
    this.facts = facts;
    this.repository = repository;
    this.codec = codec;
    this.clock = clock;
    this.stored = meters.counter("travelos.audit.stored");
    this.duplicates = meters.counter("travelos.audit.duplicates");
    this.quarantined = meters.counter("travelos.audit.quarantined");
  }

  @Transactional
  public Outcome ingest(String topic, int partition, long offset, String payload) {
    Instant now = clock.instant();
    EventEnvelope event;
    try {
      event = codec.fromJson(payload);
    } catch (RuntimeException e) {
      repository.quarantine(topic, partition, offset, now, e.getMessage(), payload);
      quarantined.increment();
      log.warn("quarantined {}-{}@{}: {}", topic, partition, offset, e.getMessage());
      return Outcome.QUARANTINED;
    }
    Span span = Span.current();
    if (span.getSpanContext().isValid()) {
      span.setAttribute("trip.id", event.correlationId());
      span.setAttribute("tenant.id", event.tenantId());
      span.setAttribute("event.type", event.eventType());
    }
    if (!repository.insertIfAbsent(event, topic, partition, offset, now)) {
      duplicates.increment();
      return Outcome.DUPLICATE;
    }
    if ("travel.trip.created".equals(event.eventType())) {
      Object tripId = event.data().get("tripId");
      Object travelerId = event.data().get("travelerId");
      if (tripId != null && travelerId != null) {
        repository.indexTrip(
            TenantId.of(event.tenantId()),
            String.valueOf(tripId),
            String.valueOf(travelerId),
            event.occurredAt());
      }
    }
    // Slice 4: a demand candidate's trail is read like a trip's (same index, its own id prefix).
    if ("travel.demand.candidate-detected".equals(event.eventType())) {
      Object candidateId = event.data().get("candidateId");
      Object travelerId = event.data().get("travelerId");
      if (candidateId != null && travelerId != null) {
        repository.indexTrip(
            TenantId.of(event.tenantId()),
            String.valueOf(candidateId),
            String.valueOf(travelerId),
            event.occurredAt());
      }
    }
    // Phase 9: the reporting facts, in the same transaction as the stored event
    facts.apply(event, now);
    stored.increment();
    return Outcome.STORED;
  }

  public enum Outcome {
    STORED,
    DUPLICATE,
    QUARANTINED
  }
}
