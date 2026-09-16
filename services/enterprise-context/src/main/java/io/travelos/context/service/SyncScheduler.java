package io.travelos.context.service;

import io.travelos.context.ContextProperties;
import io.travelos.context.model.Connector;
import io.travelos.context.model.SyncRun;
import io.travelos.context.store.ConnectorRepository;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled synchronization: every ENABLED connector without a run in flight is offered a run once
 * per interval. The claim is a single UPDATE ... RETURNING, so several instances never request the
 * same run twice.
 */
@Component
public class SyncScheduler {
  private static final Logger log = LoggerFactory.getLogger(SyncScheduler.class);
  private final ConnectorRepository connectors;
  private final SyncService sync;
  private final ContextProperties properties;
  private final Clock clock;

  public SyncScheduler(
      ConnectorRepository connectors, SyncService sync, ContextProperties properties, Clock clock) {
    this.connectors = connectors;
    this.sync = sync;
    this.properties = properties;
    this.clock = clock;
  }

  @Scheduled(fixedDelayString = "${travelos.context.scheduler-tick:10s}")
  public void tick() {
    Instant now = clock.instant();
    for (Connector c : connectors.dueForSync(now, now.plus(properties.syncInterval()))) {
      SyncRun run = sync.requestSync(c, SyncRun.Trigger.SCHEDULE, null, null);
      log.info(
          "scheduled sync {} for {} {} ({})",
          run.runId(),
          c.kind(),
          c.connectorId(),
          c.tenant().value());
    }
  }
}
