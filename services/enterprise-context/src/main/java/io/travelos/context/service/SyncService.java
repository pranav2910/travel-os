package io.travelos.context.service;

import io.grpc.Status;
import io.travelos.common.identity.Principal;
import io.travelos.common.ids.IdPrefix;
import io.travelos.common.ids.Ids;
import io.travelos.common.tenant.TenantId;
import io.travelos.context.events.DemandEvents;
import io.travelos.context.metrics.DemandMetrics;
import io.travelos.context.model.Connector;
import io.travelos.context.model.SyncRun;
import io.travelos.context.source.EnterpriseSource;
import io.travelos.context.source.SourceException;
import io.travelos.context.source.SourcePage;
import io.travelos.context.source.SourceRegistry;
import io.travelos.context.store.ConnectorRepository;
import io.travelos.context.store.SyncRunRepository;
import io.travelos.spring.outbox.Outbox;
import java.time.Clock;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Durable, repeatable synchronization. A run is requested here (an event the worker turns into a
 * workflow) and driven page by page through {@link #syncPage}: fetch, store items and revisions,
 * detect, publish, advance the checkpoint, all in one transaction. A retried page repeats the same
 * fetch and finds every item already at that revision; a crash between pages loses nothing.
 */
@Service
public class SyncService {
  private static final Logger log = LoggerFactory.getLogger(SyncService.class);
  static final int MAX_PAGES = 1000;

  private final ConnectorRepository connectors;
  private final SyncRunRepository runs;
  private final SourceRegistry sources;
  private final DemandService demand;
  private final Outbox outbox;
  private final DemandMetrics metrics;
  private final Clock clock;

  public SyncService(
      ConnectorRepository connectors,
      SyncRunRepository runs,
      SourceRegistry sources,
      DemandService demand,
      Outbox outbox,
      DemandMetrics metrics,
      Clock clock) {
    this.connectors = connectors;
    this.runs = runs;
    this.sources = sources;
    this.demand = demand;
    this.outbox = outbox;
    this.metrics = metrics;
    this.clock = clock;
  }

  /** Records the request and tells the workflow engine (through Kafka) to run it. */
  @Transactional
  public SyncRun requestSync(
      Connector connector,
      SyncRun.Trigger trigger,
      @Nullable Principal requestedBy,
      @Nullable String notificationId) {
    Instant now = clock.instant();
    SyncRun run =
        new SyncRun(
            Ids.newId(IdPrefix.SYNC_RUN),
            connector.connectorId(),
            connector.tenant(),
            trigger,
            SyncRun.Status.REQUESTED,
            connector.checkpoint(),
            connector.checkpoint(),
            0,
            0,
            0,
            0,
            "",
            null,
            null,
            requestedBy == null ? null : requestedBy.id(),
            notificationId,
            now,
            null);
    runs.insert(run);
    outbox.append(DemandEvents.syncRequested(connector, run, requestedBy, notificationId, clock));
    return run;
  }

  public record PageResult(
      String nextCursor, boolean done, int itemsSeen, int itemsChanged, int candidatesTouched) {}

  /**
   * One page of one run. gRPC-mapped: retryable source trouble becomes UNAVAILABLE /
   * RESOURCE_EXHAUSTED.
   */
  @Transactional
  public PageResult syncPage(TenantId tenant, String connectorId, String runId, String cursor) {
    Connector connector =
        connectors
            .lock(tenant, connectorId)
            .orElseThrow(
                () ->
                    Status.NOT_FOUND
                        .withDescription("connector " + connectorId)
                        .asRuntimeException());
    Instant now = clock.instant();
    SyncRun run = runs.find(tenant, runId).orElse(null);
    if (run == null) {
      run =
          new SyncRun(
              runId,
              connectorId,
              tenant,
              SyncRun.Trigger.MANUAL,
              SyncRun.Status.RUNNING,
              connector.checkpoint(),
              connector.checkpoint(),
              0,
              0,
              0,
              0,
              "",
              null,
              null,
              null,
              null,
              now,
              null);
      runs.insert(run);
    }
    if (run.status() == SyncRun.Status.COMPLETED || run.status() == SyncRun.Status.FAILED) {
      return new PageResult(cursor, true, 0, 0, 0);
    }
    if (!connectors.claimRun(tenant, connectorId, runId, now)) {
      // Another run holds the connector: this one waits its turn (a retryable answer); it never
      // runs alongside it and never fails because of it.
      throw Status.UNAVAILABLE
          .withDescription(
              "RUN_BUSY: another run ("
                  + connector.runningRunId()
                  + ") is in flight for "
                  + connectorId)
          .asRuntimeException();
    }
    EnterpriseSource<?> source =
        sources
            .find(connector.kind(), connector.provider())
            .orElseThrow(
                () ->
                    Status.FAILED_PRECONDITION
                        .withDescription("PROVIDER_UNKNOWN: " + connector.provider())
                        .asRuntimeException());
    SourcePage<?> page;
    try {
      page = source.fetch(connector, run.since(), cursor);
    } catch (SourceException e) {
      metrics.page(connector.kind(), e.code());
      log.warn(
          "{} {} page '{}': {} ({})",
          connector.kind(),
          connectorId,
          cursor,
          e.code(),
          e.getMessage());
      throw (e.retryable()
              ? ("RATE_LIMITED".equals(e.code()) ? Status.RESOURCE_EXHAUSTED : Status.UNAVAILABLE)
              : Status.FAILED_PRECONDITION)
          .withDescription(e.code() + ": " + e.getMessage())
          .asRuntimeException();
    }
    int seen = 0;
    int changed = 0;
    int touched = 0;
    for (SourcePage.Entry<?> entry : page.entries()) {
      seen++;
      DemandService.Ingested r = demand.ingest(connector, entry, runId);
      if (r.changed()) {
        changed++;
      }
      touched += r.candidatesTouched();
    }
    runs.page(runId, page.nextCursor(), page.watermark(), seen, changed, touched);
    // The durable write boundary: the checkpoint moves only with the page that justifies it, and
    // only forward. A source's empty final page reports the run's starting watermark; taking it
    // would rewind the checkpoint to before this run's items and make the next run re-see them.
    if (Watermarks.compare(page.watermark(), connector.checkpoint()) > 0) {
      connectors.advanceCheckpoint(tenant, connectorId, page.watermark(), now);
    }
    metrics.page(connector.kind(), "OK");
    if (run.pages() + 1 >= MAX_PAGES && !page.done()) {
      throw Status.FAILED_PRECONDITION
          .withDescription("TOO_MANY_PAGES: the run exceeded " + MAX_PAGES + " pages")
          .asRuntimeException();
    }
    return new PageResult(page.nextCursor(), page.done(), seen, changed, touched);
  }

  @Transactional
  public SyncRun completeSync(TenantId tenant, String connectorId, String runId) {
    Connector connector =
        connectors
            .lock(tenant, connectorId)
            .orElseThrow(
                () ->
                    Status.NOT_FOUND
                        .withDescription("connector " + connectorId)
                        .asRuntimeException());
    SyncRun run =
        runs.find(tenant, runId)
            .orElseThrow(
                () -> Status.NOT_FOUND.withDescription("run " + runId).asRuntimeException());
    if (run.status() == SyncRun.Status.COMPLETED || run.status() == SyncRun.Status.FAILED) {
      return run;
    }
    Instant now = clock.instant();
    runs.finish(runId, SyncRun.Status.COMPLETED, null, null, now);
    connectors.finishRun(tenant, connectorId, runId, true, null, null, now);
    SyncRun done = runs.find(tenant, runId).orElseThrow();
    outbox.append(DemandEvents.syncCompleted(connector, done, clock));
    metrics.run(connector.kind(), "COMPLETED");
    return done;
  }

  @Transactional
  public SyncRun failSync(
      TenantId tenant, String connectorId, String runId, String code, @Nullable String message) {
    Connector connector =
        connectors
            .lock(tenant, connectorId)
            .orElseThrow(
                () ->
                    Status.NOT_FOUND
                        .withDescription("connector " + connectorId)
                        .asRuntimeException());
    SyncRun run =
        runs.find(tenant, runId)
            .orElseThrow(
                () -> Status.NOT_FOUND.withDescription("run " + runId).asRuntimeException());
    if (run.status() == SyncRun.Status.COMPLETED || run.status() == SyncRun.Status.FAILED) {
      return run;
    }
    Instant now = clock.instant();
    runs.finish(runId, SyncRun.Status.FAILED, code, message, now);
    connectors.finishRun(tenant, connectorId, runId, false, code, message, now);
    SyncRun failed = runs.find(tenant, runId).orElseThrow();
    outbox.append(DemandEvents.syncFailed(connector, failed, code, message, clock));
    metrics.run(connector.kind(), "FAILED");
    return failed;
  }
}
