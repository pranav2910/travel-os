package io.travelos.policy.governance;

import io.travelos.common.money.Money;
import io.travelos.common.tenant.TenantId;
import io.travelos.events.EventCodec;
import io.travelos.events.EventEnvelope;
import java.time.Clock;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase 7: budget settlement from the platform's own facts. A booked trip commits its reservation
 * at what it cost; a cancelled or failed trip releases it (and gives back a committed amount).
 * Exactly once per event id.
 */
@Component
public class TripEventsListener {
  private static final Logger log = LoggerFactory.getLogger(TripEventsListener.class);
  private final GovernanceRepository store;
  private final GovernanceService governance;
  private final Clock clock;
  private final EventCodec codec = new EventCodec();

  public TripEventsListener(GovernanceRepository store, GovernanceService governance, Clock clock) {
    this.store = store;
    this.governance = governance;
    this.clock = clock;
  }

  @KafkaListener(
      id = "policy-budgets",
      topics = "travel.trip",
      groupId = "${spring.kafka.consumer.group-id:policy}")
  public void onRecord(ConsumerRecord<String, String> record) {
    EventEnvelope event;
    try {
      event = codec.fromJson(record.value());
    } catch (RuntimeException e) {
      log.error("unreadable event on {}, skipping: {}", record.topic(), e.getMessage());
      return;
    }
    settle(event);
  }

  @Transactional
  public void settle(EventEnvelope event) {
    boolean commit;
    switch (event.eventType()) {
      case "travel.trip.booked" -> commit = true;
      case "travel.trip.cancelled", "travel.trip.failed" -> commit = false;
      default -> {
        return;
      }
    }
    if (!store.markProcessed(event.eventId(), event.eventType(), clock.instant())) {
      return;
    }
    Object tripId = event.data().get("tripId");
    if (tripId == null) {
      return;
    }
    Money amount = null;
    if (event.data().get("total") instanceof Map<?, ?> m
        && m.get("currency") != null
        && m.get("amountMinor") != null) {
      amount =
          Money.of(String.valueOf(m.get("currency")), ((Number) m.get("amountMinor")).longValue());
    }
    GovernanceService.ReserveResult r =
        governance.settle(TenantId.of(event.tenantId()), String.valueOf(tripId), commit, amount);
    if (r.status() != GovernanceService.ReserveStatus.NO_BUDGET) {
      log.info("trip {} budget {}: {}", tripId, r.budgetId(), r.message());
    }
  }
}
