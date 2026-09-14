package io.travelos.supplier.notification;

import io.travelos.spring.web.error.ApiException;
import io.travelos.supplier.AirSupplier;
import io.travelos.supplier.SupplierProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Supplier webhooks. No JWT here (suppliers are not tenants): each provider signs the raw body with
 * its shared secret, HMAC-SHA256, and we verify in constant time before reading a byte of it. The
 * path is opened in travelos.web.public-paths; everything else on the gateway still needs a token.
 */
@RestController
@RequestMapping(path = "/api/v1/suppliers/{provider}/events")
public class SupplierNotificationController {

  static final String SIGNATURE_HEADER = "X-Supplier-Signature";

  private final SupplierNotificationService notifications;
  private final SupplierProperties properties;

  public SupplierNotificationController(
      SupplierNotificationService notifications, SupplierProperties properties) {
    this.notifications = notifications;
    this.properties = properties;
  }

  public record Receipt(String disruptionId, String eventId, boolean duplicate) {}

  @PostMapping(consumes = "application/json", produces = "application/json")
  public ResponseEntity<Receipt> receive(
      @PathVariable String provider,
      @RequestHeader(name = SIGNATURE_HEADER, required = false) String signature,
      @RequestBody byte[] body) {
    String secret = properties.adapter(provider).webhookSecret();
    if (secret == null || secret.isBlank()) {
      throw new ApiException.Forbidden(
          "WEBHOOK_NOT_CONFIGURED", "no webhook secret configured for " + provider);
    }
    if (!verify(secret, body, signature)) {
      throw new ApiException(
          HttpStatus.UNAUTHORIZED, "BAD_SIGNATURE", "the request is not signed by " + provider);
    }
    SupplierNotificationService.Outcome outcome;
    try {
      outcome = notifications.ingest(provider, new String(body, StandardCharsets.UTF_8));
    } catch (AirSupplier.SupplierException e) {
      throw switch (e.code()) {
        case "PROVIDER_UNKNOWN", "ORDER_UNKNOWN" ->
            new ApiException.NotFound(e.code(), e.getMessage());
        default -> new ApiException.Unprocessable(e.code(), e.getMessage());
      };
    }
    return ResponseEntity.status(outcome.duplicate() ? HttpStatus.OK : HttpStatus.ACCEPTED)
        .body(new Receipt(outcome.disruptionId(), outcome.eventId(), outcome.duplicate()));
  }

  static boolean verify(String secret, byte[] body, String header) {
    if (header == null || !header.toLowerCase(Locale.ROOT).startsWith("sha256=")) {
      return false;
    }
    byte[] expected = hmac(secret, body);
    byte[] given;
    try {
      given = HexFormat.of().parseHex(header.substring("sha256=".length()).trim());
    } catch (IllegalArgumentException e) {
      return false;
    }
    return MessageDigest.isEqual(expected, given);
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

  public static String sign(String secret, byte[] body) {
    return "sha256=" + HexFormat.of().formatHex(hmac(secret, body));
  }
}
