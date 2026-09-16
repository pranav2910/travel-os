package io.travelos.context.api;

import io.travelos.context.model.Connector;
import io.travelos.context.model.ConnectorKind;
import io.travelos.context.model.SyncRun;
import io.travelos.context.service.ConnectorService;
import io.travelos.spring.web.auth.RequestPrincipal;
import io.travelos.spring.web.error.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tenant-scoped connectors: configuration, observable sync status, and sandbox fixture controls.
 */
@RestController
@RequestMapping(path = "/api/v1/connectors", produces = "application/json")
public class ConnectorController {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private final ConnectorService connectors;

  public ConnectorController(ConnectorService connectors) {
    this.connectors = connectors;
  }

  public record ConnectorView(
      String connectorId,
      String tenantId,
      String kind,
      String provider,
      String status,
      boolean simulated,
      JsonNode config,
      String checkpoint,
      @Nullable String runningRunId,
      @Nullable Instant nextSyncAt,
      @Nullable String lastRunId,
      @Nullable Instant lastSyncAt,
      @Nullable Instant lastSuccessAt,
      @Nullable String lastErrorCode,
      @Nullable String lastErrorMessage,
      long version,
      String createdBy,
      Instant createdAt,
      Instant updatedAt) {
    static ConnectorView from(Connector c) {
      return new ConnectorView(
          c.connectorId(),
          c.tenant().value(),
          c.kind().name(),
          c.provider(),
          c.status().name(),
          c.simulated(),
          JSON.readTree(c.configJson()),
          c.checkpoint(),
          c.runningRunId(),
          c.nextSyncAt(),
          c.lastRunId(),
          c.lastSyncAt(),
          c.lastSuccessAt(),
          c.lastErrorCode(),
          c.lastErrorMessage(),
          c.version(),
          c.createdBy(),
          c.createdAt(),
          c.updatedAt());
    }
  }

  public record RunView(
      String runId,
      String connectorId,
      String trigger,
      String status,
      String since,
      String watermark,
      int pages,
      int itemsSeen,
      int itemsChanged,
      int candidatesTouched,
      @Nullable String failureCode,
      @Nullable String failureMessage,
      @Nullable String requestedBy,
      @Nullable String notificationId,
      Instant startedAt,
      @Nullable Instant finishedAt) {
    static RunView from(SyncRun r) {
      return new RunView(
          r.runId(),
          r.connectorId(),
          r.trigger().name(),
          r.status().name(),
          r.since(),
          r.watermark(),
          r.pages(),
          r.itemsSeen(),
          r.itemsChanged(),
          r.candidatesTouched(),
          r.failureCode(),
          r.failureMessage(),
          r.requestedBy(),
          r.notificationId(),
          r.startedAt(),
          r.finishedAt());
    }
  }

  public record CreateRequest(
      @NotNull ConnectorKind kind,
      @NotBlank String provider,
      @Nullable Map<String, Object> config) {}

  @PostMapping(consumes = "application/json")
  public ResponseEntity<ConnectorView> create(
      @AuthenticationPrincipal RequestPrincipal me, @Valid @RequestBody CreateRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            ConnectorView.from(
                connectors.create(me, request.kind(), request.provider(), request.config())));
  }

  @GetMapping
  public List<ConnectorView> list(@AuthenticationPrincipal RequestPrincipal me) {
    return connectors.list(me).stream().map(ConnectorView::from).toList();
  }

  @GetMapping("/{connectorId}")
  public ConnectorView get(
      @AuthenticationPrincipal RequestPrincipal me, @PathVariable String connectorId) {
    return ConnectorView.from(connectors.get(me, connectorId));
  }

  @GetMapping("/{connectorId}/runs")
  public List<RunView> runs(
      @AuthenticationPrincipal RequestPrincipal me, @PathVariable String connectorId) {
    return connectors.runs(me, connectorId).stream().map(RunView::from).toList();
  }

  public record StatusRequest(@NotNull Connector.Status status) {}

  @PostMapping(path = "/{connectorId}/status", consumes = "application/json")
  public ConnectorView status(
      @AuthenticationPrincipal RequestPrincipal me,
      @PathVariable String connectorId,
      @Valid @RequestBody StatusRequest request) {
    return ConnectorView.from(connectors.setStatus(me, connectorId, request.status()));
  }

  public record ConfigRequest(@Nullable Map<String, Object> config) {}

  /**
   * Replace the connector's configuration (never a secret). {"scheduled": false} opts out of the
   * scheduler.
   */
  @PostMapping(path = "/{connectorId}/config", consumes = "application/json")
  public ConnectorView config(
      @AuthenticationPrincipal RequestPrincipal me,
      @PathVariable String connectorId,
      @Valid @RequestBody ConfigRequest request) {
    return ConnectorView.from(connectors.updateConfig(me, connectorId, request.config()));
  }

  /** Ask for a synchronization now; the run id is what the workflow engine will report on. */
  @PostMapping("/{connectorId}/sync")
  public ResponseEntity<RunView> sync(
      @AuthenticationPrincipal RequestPrincipal me, @PathVariable String connectorId) {
    return ResponseEntity.status(HttpStatus.ACCEPTED)
        .body(RunView.from(connectors.requestSync(me, connectorId)));
  }

  // ------------------------------------------------------------------ SIMULATED fixtures

  public record SeedRequest(@NotNull List<ConnectorService.SandboxItem> items) {}

  public record SeedView(int appended) {}

  /** What the sandbox system holds next. Sandbox providers only; a live provider refuses. */
  @PostMapping(path = "/{connectorId}/sandbox/items", consumes = "application/json")
  public SeedView seed(
      @AuthenticationPrincipal RequestPrincipal me,
      @PathVariable String connectorId,
      @Valid @RequestBody SeedRequest request) {
    if (request.items().isEmpty()) {
      throw new ApiException.Unprocessable("NO_ITEMS", "items must not be empty");
    }
    return new SeedView(connectors.seed(me, connectorId, request.items()));
  }

  public record FaultRequest(int unavailableCalls, @Nullable Integer rateLimitPage) {}

  @PostMapping(path = "/{connectorId}/sandbox/faults", consumes = "application/json")
  public Map<String, Object> faults(
      @AuthenticationPrincipal RequestPrincipal me,
      @PathVariable String connectorId,
      @Valid @RequestBody FaultRequest request) {
    int page = request.rateLimitPage() == null ? -1 : request.rateLimitPage();
    connectors.faults(me, connectorId, Math.max(0, request.unavailableCalls()), page);
    return Map.of(
        "unavailableCalls", Math.max(0, request.unavailableCalls()), "rateLimitPage", page);
  }
}
