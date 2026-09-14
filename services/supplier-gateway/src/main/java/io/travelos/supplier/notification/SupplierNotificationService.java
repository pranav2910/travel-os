package io.travelos.supplier.notification;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.travelos.common.ids.IdPrefix;
import io.travelos.common.ids.Ids;
import io.travelos.events.EventEnvelope;
import io.travelos.spring.outbox.Outbox;
import io.travelos.supplier.AirSupplier;
import io.travelos.supplier.SupplierRegistry;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Supplier notices in, one normalized {@code travel.disruption.detected} out, exactly once per
 * supplier event. Dedupe and the outbox append happen in the same transaction, so a webhook that is
 * redelivered (they always are) maps to the disruption it already produced and publishes nothing.
 */
@Service
public class SupplierNotificationService {

  private static final Logger log = LoggerFactory.getLogger(SupplierNotificationService.class);

  public record Outcome(String disruptionId, String eventId, boolean duplicate) {}

  private final SupplierRegistry registry;
  private final SupplierOrderRefRepository refs;
  private final JdbcClient jdbc;
  private final Outbox outbox;
  private final Clock clock;
  private final Counter received;
  private final Counter duplicates;

  public SupplierNotificationService(
      SupplierRegistry registry,
      SupplierOrderRefRepository refs,
      JdbcClient jdbc,
      Outbox outbox,
      Clock clock,
      MeterRegistry meters) {
    this.registry = registry;
    this.refs = refs;
    this.jdbc = jdbc;
    this.outbox = outbox;
    this.clock = clock;
    this.received = meters.counter("travelos.supplier.notifications.received");
    this.duplicates = meters.counter("travelos.supplier.notifications.duplicate");
  }

  @Transactional
  public Outcome ingest(String provider, String rawPayload) {
    AirSupplier adapter =
        registry
            .find(provider)
            .orElseThrow(
                () ->
                    new AirSupplier.SupplierException(
                        "PROVIDER_UNKNOWN", "no adapter for " + provider, false));
    received.increment();
    SupplierNotification normalized = adapter.normalizeNotification(rawPayload);
    SupplierOrderRefRepository.Ref ref =
        refs.find(provider, normalized.externalOrderId())
            .orElseThrow(
                () ->
                    new AirSupplier.SupplierException(
                        "ORDER_UNKNOWN",
                        provider
                            + " order "
                            + normalized.externalOrderId()
                            + " was not booked here",
                        false));
    SupplierNotification notice =
        normalized.withRef(ref.tenantId(), ref.correlationId(), ref.recordLocator());
    Optional<Outcome> existing = find(provider, notice.supplierEventId());
    if (existing.isPresent()) {
      duplicates.increment();
      log.info(
          "duplicate notice {} from {}: already disruption {}",
          notice.supplierEventId(),
          provider,
          existing.get().disruptionId());
      return existing.get();
    }
    String disruptionId = Ids.newId(IdPrefix.DISRUPTION);
    EventEnvelope event = DisruptionEvents.detected(disruptionId, notice, clock);
    jdbc.sql(
            """
            INSERT INTO supplier_notification (provider, supplier_event_id, disruption_id, tenant_id, event_id, received_at, payload)
            VALUES (:provider, :eventId, :disruption, :tenant, :evt, :at, CAST(:payload AS jsonb))
            """)
        .param("provider", provider)
        .param("eventId", notice.supplierEventId())
        .param("disruption", disruptionId)
        .param("tenant", notice.tenantId())
        .param("evt", event.eventId())
        .param("at", Timestamp.from(clock.instant()))
        .param("payload", rawPayload)
        .update();
    adapter.applyNotification(notice, rawPayload);
    outbox.append(event);
    log.info(
        "notice {} from {}: disruption {} ({} {}) for {}",
        notice.supplierEventId(),
        provider,
        disruptionId,
        notice.type(),
        notice.affected().flightNumber(),
        notice.correlationId());
    return new Outcome(disruptionId, event.eventId(), false);
  }

  private Optional<Outcome> find(String provider, String supplierEventId) {
    return jdbc.sql(
            "SELECT disruption_id, event_id FROM supplier_notification WHERE provider = :p AND supplier_event_id = :e")
        .param("p", provider)
        .param("e", supplierEventId)
        .query(
            (rs, i) -> new Outcome(rs.getString("disruption_id"), rs.getString("event_id"), true))
        .optional();
  }
}
