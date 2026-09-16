package io.travelos.context.api;

import io.travelos.context.service.NotificationService;
import io.travelos.spring.web.error.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Provider notifications. No JWT here (a provider is not a tenant): each provider signs the raw
 * body with its shared secret and we verify before reading a byte. The path is opened in
 * travelos.web.public-paths; everything else on this service needs a token.
 */
@RestController
@RequestMapping(path = "/api/v1/connectors/{provider}/events")
public class ConnectorNotificationController {
  static final String SIGNATURE_HEADER = "X-Connector-Signature";
  private final NotificationService notifications;

  public ConnectorNotificationController(NotificationService notifications) {
    this.notifications = notifications;
  }

  @PostMapping(consumes = "application/json", produces = "application/json")
  public ResponseEntity<NotificationService.Receipt> receive(
      @PathVariable String provider,
      @RequestHeader(name = SIGNATURE_HEADER, required = false) String signature,
      @RequestBody byte[] body) {
    String secret = notifications.secretFor(provider);
    if (secret == null || secret.isBlank()) {
      throw new ApiException.Forbidden(
          "WEBHOOK_NOT_CONFIGURED", "no webhook secret configured for " + provider);
    }
    if (!NotificationService.verify(secret, body, signature)) {
      throw new ApiException(
          HttpStatus.UNAUTHORIZED, "BAD_SIGNATURE", "the request is not signed by " + provider);
    }
    NotificationService.Receipt receipt = notifications.ingest(provider, body);
    return ResponseEntity.status(receipt.duplicate() ? HttpStatus.OK : HttpStatus.ACCEPTED)
        .body(receipt);
  }
}
