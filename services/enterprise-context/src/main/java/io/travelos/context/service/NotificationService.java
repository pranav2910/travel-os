package io.travelos.context.service;

import io.travelos.common.tenant.TenantId;
import io.travelos.context.ContextProperties;
import io.travelos.context.metrics.DemandMetrics;
import io.travelos.context.model.Connector;
import io.travelos.context.model.SyncRun;
import io.travelos.context.store.ConnectorRepository;
import io.travelos.context.store.NotificationReceiptRepository;
import io.travelos.context.store.SandboxStore;
import io.travelos.spring.web.error.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Incoming notifications from providers ("something changed"). Signed with the provider's shared
 * secret (HMAC-SHA256 over the raw body, verified in constant time), deduplicated by the provider's
 * event id, and answered with the run they started. The body is never trusted for identity: it
 * names a connector, and the connector's own configuration decides everything else.
 */
@Service
public class NotificationService {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private final ConnectorRepository connectors;
  private final NotificationReceiptRepository receipts;
  private final SandboxStore sandbox;
  private final SyncService sync;
  private final ContextProperties properties;
  private final DemandMetrics metrics;
  private final Clock clock;

  public NotificationService(
      ConnectorRepository connectors,
      NotificationReceiptRepository receipts,
      SandboxStore sandbox,
      SyncService sync,
      ContextProperties properties,
      DemandMetrics metrics,
      Clock clock) {
    this.connectors = connectors;
    this.receipts = receipts;
    this.sandbox = sandbox;
    this.sync = sync;
    this.properties = properties;
    this.metrics = metrics;
    this.clock = clock;
  }

  /**
   * The sandbox provider's notice: which connector changed and (a convenience) what it now holds.
   */
  public record Notice(
      String eventId,
      String tenantId,
      String connectorId,
      @Nullable List<ConnectorService.SandboxItem> items) {}

  public record Receipt(String runId, String eventId, boolean duplicate) {}

  public @Nullable String secretFor(String provider) {
    return provider.startsWith("sandbox-") ? properties.connectors().sandboxWebhookSecret() : null;
  }

  @Transactional
  public Receipt ingest(String provider, byte[] body) {
    Notice notice;
    try {
      notice = JSON.readValue(new String(body, StandardCharsets.UTF_8), Notice.class);
    } catch (RuntimeException e) {
      metrics.notification("REJECTED");
      throw new ApiException.Unprocessable(
          "INVALID_NOTICE", "unreadable notice: " + e.getMessage());
    }
    if (notice.eventId() == null
        || notice.eventId().isBlank()
        || notice.tenantId() == null
        || notice.connectorId() == null) {
      metrics.notification("REJECTED");
      throw new ApiException.Unprocessable(
          "INVALID_NOTICE", "eventId, tenantId and connectorId are required");
    }
    TenantId tenant = TenantId.of(notice.tenantId());
    Connector c =
        connectors
            .find(tenant, notice.connectorId())
            .filter(x -> x.provider().equals(provider))
            .orElseThrow(() -> new ApiException.NotFound("connector", notice.connectorId()));
    String existing = receipts.runOf(provider, notice.eventId());
    if (existing != null) {
      metrics.notification("DUPLICATE");
      return new Receipt(existing, notice.eventId(), true);
    }
    Instant now = clock.instant();
    if (c.simulated() && notice.items() != null) {
      for (ConnectorService.SandboxItem it : notice.items()) {
        sandbox.append(
            c.tenant().value(),
            c.connectorId(),
            it.sourceId(),
            it.revision(),
            it.deleted() != null && it.deleted(),
            JSON.writeValueAsString(it.payload() == null ? Map.of() : it.payload()),
            now);
      }
    }
    SyncRun run = sync.requestSync(c, SyncRun.Trigger.WEBHOOK, null, notice.eventId());
    if (!receipts.record(
        provider, notice.eventId(), tenant.value(), c.connectorId(), run.runId(), now)) {
      metrics.notification("DUPLICATE");
      return new Receipt(receipts.runOf(provider, notice.eventId()), notice.eventId(), true);
    }
    metrics.notification("ACCEPTED");
    return new Receipt(run.runId(), notice.eventId(), false);
  }

  public static boolean verify(String secret, byte[] body, @Nullable String header) {
    if (header == null || !header.toLowerCase(Locale.ROOT).startsWith("sha256=")) {
      return false;
    }
    byte[] given;
    try {
      given = HexFormat.of().parseHex(header.substring("sha256=".length()).trim());
    } catch (IllegalArgumentException e) {
      return false;
    }
    return MessageDigest.isEqual(hmac(secret, body), given);
  }

  public static String sign(String secret, byte[] body) {
    return "sha256=" + HexFormat.of().formatHex(hmac(secret, body));
  }

  static byte[] hmac(String secret, byte[] body) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return mac.doFinal(body);
    } catch (java.security.GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  static ApiException unauthorized(String provider) {
    return new ApiException(
        HttpStatus.UNAUTHORIZED, "BAD_SIGNATURE", "the request is not signed by " + provider);
  }
}
