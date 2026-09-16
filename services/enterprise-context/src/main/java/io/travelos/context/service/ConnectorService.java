package io.travelos.context.service;

import io.travelos.common.ids.IdPrefix;
import io.travelos.common.ids.Ids;
import io.travelos.context.model.Connector;
import io.travelos.context.model.ConnectorKind;
import io.travelos.context.model.SyncRun;
import io.travelos.context.source.SourceRegistry;
import io.travelos.context.store.ConnectorRepository;
import io.travelos.context.store.SandboxStore;
import io.travelos.context.store.SyncRunRepository;
import io.travelos.spring.web.auth.RequestPrincipal;
import io.travelos.spring.web.error.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** Tenant-scoped connector configuration and, for SIMULATED providers, the fixture controls. */
@Service
public class ConnectorService {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private final ConnectorRepository connectors;
  private final SyncRunRepository runs;
  private final SourceRegistry registry;
  private final SandboxStore sandbox;
  private final SyncService sync;
  private final Clock clock;

  public ConnectorService(
      ConnectorRepository connectors,
      SyncRunRepository runs,
      SourceRegistry registry,
      SandboxStore sandbox,
      SyncService sync,
      Clock clock) {
    this.connectors = connectors;
    this.runs = runs;
    this.registry = registry;
    this.sandbox = sandbox;
    this.sync = sync;
    this.clock = clock;
  }

  @Transactional
  public Connector create(
      RequestPrincipal me,
      ConnectorKind kind,
      String provider,
      @Nullable Map<String, Object> config) {
    requireAdmin(me);
    if (registry.find(kind, provider).isEmpty()) {
      throw new ApiException.Unprocessable(
          "PROVIDER_UNKNOWN",
          "no " + kind + " provider '" + provider + "'; known: " + registry.providers());
    }
    requireNoSecrets(config);
    if (connectors.findByKind(me.tenant(), kind, provider).isPresent()) {
      throw new ApiException.Conflict(
          "CONNECTOR_EXISTS", "a " + kind + " connector for " + provider + " already exists");
    }
    Instant now = clock.instant();
    Connector c =
        new Connector(
            Ids.newId(IdPrefix.CONNECTOR),
            me.tenant(),
            kind,
            provider,
            Connector.Status.ENABLED,
            JSON.writeValueAsString(config == null ? Map.of() : config),
            "",
            null,
            now,
            null,
            null,
            null,
            null,
            null,
            0,
            me.principal().id(),
            now,
            now);
    connectors.insert(c);
    return c;
  }

  @Transactional(readOnly = true)
  public List<Connector> list(RequestPrincipal me) {
    if (!DemandAccess.canReadConnectors(me)) {
      throw new ApiException.Forbidden(
          "NOT_AN_ADMIN", "only TRAVEL_ADMIN or FINANCE may read connectors");
    }
    return connectors.list(me.tenant());
  }

  @Transactional(readOnly = true)
  public Connector get(RequestPrincipal me, String connectorId) {
    // Tenant scope first (another tenant's connector does not exist: 404), then the role (403).
    Connector c =
        connectors
            .find(me.tenant(), connectorId)
            .orElseThrow(() -> new ApiException.NotFound("connector", connectorId));
    if (!DemandAccess.canReadConnectors(me)) {
      throw new ApiException.Forbidden(
          "NOT_AN_ADMIN", "only TRAVEL_ADMIN or FINANCE may read connectors");
    }
    return c;
  }

  @Transactional(readOnly = true)
  public List<SyncRun> runs(RequestPrincipal me, String connectorId) {
    Connector c = get(me, connectorId);
    return runs.byConnector(me.tenant(), c.connectorId(), 20);
  }

  @Transactional
  public Connector setStatus(RequestPrincipal me, String connectorId, Connector.Status status) {
    requireAdmin(me);
    Connector c =
        connectors
            .find(me.tenant(), connectorId)
            .orElseThrow(() -> new ApiException.NotFound("connector", connectorId));
    connectors.setStatus(me.tenant(), connectorId, status, clock.instant());
    return connectors.find(me.tenant(), c.connectorId()).orElseThrow();
  }

  /** Replaces the (secret-free) configuration; the schedule is re-evaluated on the next tick. */
  @Transactional
  public Connector updateConfig(
      RequestPrincipal me, String connectorId, @Nullable Map<String, Object> config) {
    requireAdmin(me);
    Connector c =
        connectors
            .find(me.tenant(), connectorId)
            .orElseThrow(() -> new ApiException.NotFound("connector", connectorId));
    requireNoSecrets(config);
    connectors.updateConfig(
        me.tenant(),
        c.connectorId(),
        JSON.writeValueAsString(config == null ? Map.of() : config),
        clock.instant());
    return connectors.find(me.tenant(), c.connectorId()).orElseThrow();
  }

  /** A person asks for a synchronization now. */
  @Transactional
  public SyncRun requestSync(RequestPrincipal me, String connectorId) {
    requireAdmin(me);
    Connector c =
        connectors
            .find(me.tenant(), connectorId)
            .orElseThrow(() -> new ApiException.NotFound("connector", connectorId));
    if (c.status() != Connector.Status.ENABLED) {
      throw new ApiException.Conflict("CONNECTOR_DISABLED", "enable the connector first");
    }
    return sync.requestSync(c, SyncRun.Trigger.MANUAL, me.principal(), null);
  }

  // ------------------------------------------------------------------ SIMULATED fixture controls

  public record SandboxItem(
      String sourceId,
      long revision,
      @Nullable Boolean deleted,
      @Nullable Map<String, Object> payload) {}

  /** Appends items to what the sandbox system "holds": the next sync sees them, in this order. */
  @Transactional
  public int seed(RequestPrincipal me, String connectorId, List<SandboxItem> items) {
    requireAdmin(me);
    Connector c = simulated(me, connectorId);
    Instant now = clock.instant();
    for (SandboxItem it : items) {
      sandbox.append(
          c.tenant().value(),
          c.connectorId(),
          it.sourceId(),
          it.revision(),
          it.deleted() != null && it.deleted(),
          JSON.writeValueAsString(it.payload() == null ? Map.of() : it.payload()),
          now);
    }
    return items.size();
  }

  @Transactional
  public void faults(
      RequestPrincipal me, String connectorId, int unavailableCalls, int rateLimitPage) {
    requireAdmin(me);
    Connector c = simulated(me, connectorId);
    sandbox.setFaults(c.connectorId(), unavailableCalls, rateLimitPage, clock.instant());
  }

  private Connector simulated(RequestPrincipal me, String connectorId) {
    Connector c =
        connectors
            .find(me.tenant(), connectorId)
            .orElseThrow(() -> new ApiException.NotFound("connector", connectorId));
    if (!c.simulated()) {
      throw new ApiException.Conflict(
          "NOT_SIMULATED",
          c.provider() + " is a live provider; fixtures apply to sandbox providers only");
    }
    return c;
  }

  private static void requireNoSecrets(@Nullable Map<String, Object> config) {
    if (config != null
        && config.keySet().stream()
            .map(k -> k.toLowerCase(java.util.Locale.ROOT))
            .anyMatch(k -> k.contains("secret") || k.contains("password") || k.contains("token"))) {
      throw new ApiException.Unprocessable(
          "SECRET_IN_CONFIG",
          "connector configuration must not carry secrets; name them by reference and provide them to the service environment");
    }
  }

  private static void requireAdmin(RequestPrincipal me) {
    if (!DemandAccess.canManageConnectors(me)) {
      throw new ApiException.Forbidden("NOT_AN_ADMIN", "only TRAVEL_ADMIN may manage connectors");
    }
  }
}
