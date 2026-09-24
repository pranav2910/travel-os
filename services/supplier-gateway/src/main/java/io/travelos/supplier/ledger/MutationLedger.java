package io.travelos.supplier.ledger;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.Parser;
import io.travelos.common.ids.IdPrefix;
import io.travelos.common.ids.Ids;
import io.travelos.contracts.supplier.v1.BookingStatus;
import io.travelos.contracts.supplier.v1.GetBookingStatusRequest;
import io.travelos.contracts.supplier.v1.SupplierOrderStatus;
import io.travelos.supplier.AirSupplier.SupplierException;
import io.travelos.supplier.SupplierAdapter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Phase 4 (ADR-0016): the gateway writes a mutation down before it calls the supplier, keeps the
 * answer, and turns a lost answer into a known unknown.
 *
 * <ul>
 *   <li>Same key, same request, answer known: the stored answer, the supplier is not called.
 *   <li>Same key, different request: refused ({@code IDEMPOTENCY_KEY_REUSED}).
 *   <li>Same key, outcome unknown: reconciled by lookup when the adapter can find the booking by
 *       our key; retried when the supplier honours the key itself; otherwise refused with {@code
 *       OUTCOME_UNKNOWN}, which the Order service records as an exposure for a person.
 *   <li>A final refusal from the supplier is remembered as FAILED and answered again the same way.
 * </ul>
 */
@Component
public class MutationLedger {
  private static final Logger log = LoggerFactory.getLogger(MutationLedger.class);

  private final MutationAttemptRepository attempts;
  private final Clock clock;

  public MutationLedger(MutationAttemptRepository attempts, Clock clock) {
    this.attempts = attempts;
    this.clock = clock;
  }

  /** What a mutation looks like to the ledger, independent of the command type. */
  public record Mutation<T extends Message>(
      String tenant,
      SupplierAdapter adapter,
      MutationAttempt.Command command,
      String idempotencyKey,
      Message request,
      @Nullable String correlationId,
      Parser<T> parser,
      Function<T, String> externalRef,
      Function<SupplierAdapter, T> call) {}

  public <T extends Message> T run(Mutation<T> m) {
    if (m.idempotencyKey().isBlank()) {
      throw new SupplierException(
          "IDEMPOTENCY_KEY_REQUIRED", m.command() + " needs ctx.idempotency_key", false);
    }
    String provider = m.adapter().provider();
    String digest = digest(m.request());
    Instant now = clock.instant();
    Optional<MutationAttempt> existing =
        attempts.find(m.tenant(), provider, m.command(), m.idempotencyKey());
    MutationAttempt attempt;
    if (existing.isPresent()) {
      attempt = existing.get();
      if (!attempt.requestDigest().equals(digest)) {
        throw new SupplierException(
            "IDEMPOTENCY_KEY_REUSED",
            m.idempotencyKey()
                + " was already used for a different "
                + m.command()
                + " at "
                + provider,
            false);
      }
      switch (attempt.status()) {
        case SUCCEEDED -> {
          log.info("{} {} {}: answered from the ledger", provider, m.command(), m.idempotencyKey());
          return parse(m.parser(), attempt.response());
        }
        case FAILED -> {
          throw new SupplierException(
              attempt.failureCode() == null ? "SUPPLIER_REFUSED" : attempt.failureCode(),
              (attempt.failureMessage() == null ? "refused earlier" : attempt.failureMessage())
                  + " (from the ledger)",
              false);
        }
        case STARTED, UNKNOWN -> {
          T reconciled = reconcile(m, attempt, now);
          if (reconciled != null) {
            return reconciled;
          }
          if (!m.adapter().capabilities().getMutationsIdempotent()) {
            throw new SupplierException(
                "OUTCOME_UNKNOWN",
                provider
                    + " may or may not have executed "
                    + m.command()
                    + " "
                    + m.idempotencyKey()
                    + "; it neither honours our key nor lets us look the booking up, so nobody"
                    + " retries it: a person reconciles",
                false);
          }
          attempts.anotherCall(attempt.attemptId(), now);
        }
      }
    } else {
      attempt =
          new MutationAttempt(
              Ids.newId(IdPrefix.SUPPLIER_ATTEMPT),
              m.tenant(),
              provider,
              m.command(),
              m.idempotencyKey(),
              digest,
              MutationAttempt.Status.STARTED,
              null,
              null,
              null,
              null,
              1,
              m.correlationId(),
              now,
              now);
      if (attempts.insertStarted(attempt) == 0) {
        // a concurrent call with the same key got there first: answer from its outcome
        return run(m);
      }
    }
    try {
      T response = m.call().apply(m.adapter());
      attempts.succeeded(
          attempt.attemptId(),
          m.externalRef().apply(response),
          response.toByteArray(),
          clock.instant());
      return response;
    } catch (SupplierException e) {
      if (e.retryable()) {
        attempts.unknown(attempt.attemptId(), e.code(), e.getMessage(), clock.instant());
      } else {
        attempts.failed(attempt.attemptId(), e.code(), e.getMessage(), clock.instant());
      }
      throw e;
    } catch (RuntimeException e) {
      // transport trouble, a timeout, a bug: the supplier may have acted
      attempts.unknown(
          attempt.attemptId(), "TRANSPORT", String.valueOf(e.getMessage()), clock.instant());
      throw e;
    }
  }

  /** Asks the supplier what it did with our key; adopts a booking it made, never makes it twice. */
  private <T extends Message> @Nullable T reconcile(
      Mutation<T> m, MutationAttempt attempt, Instant now) {
    if (!m.adapter().capabilities().getReconciliationByKeySupported()
        && !m.adapter().capabilities().getStatusLookupSupported()) {
      return null;
    }
    BookingStatus status;
    try {
      status =
          m.adapter()
              .bookingStatus(
                  GetBookingStatusRequest.newBuilder()
                      .setProvider(m.adapter().provider())
                      .setIdempotencyKey(m.idempotencyKey())
                      .setExternalOrderId(
                          attempt.externalRef() == null ? "" : attempt.externalRef())
                      .build());
    } catch (SupplierException e) {
      log.warn("{}: reconciliation lookup failed ({})", m.adapter().provider(), e.code());
      return null;
    }
    if (status.getStatus() == SupplierOrderStatus.NOT_FOUND
        || status.getStatus() == SupplierOrderStatus.SUPPLIER_ORDER_STATUS_UNSPECIFIED) {
      return null;
    }
    T adopted = Reconciliation.fromStatus(m.command(), status, m.parser());
    if (adopted == null) {
      return null;
    }
    attempts.succeeded(
        attempt.attemptId(), status.getExternalOrderId(), adopted.toByteArray(), now);
    log.warn(
        "{} {} {}: outcome reconciled by lookup ({})",
        m.adapter().provider(),
        m.command(),
        m.idempotencyKey(),
        status.getStatus());
    return adopted;
  }

  private static <T extends Message> T parse(Parser<T> parser, byte @Nullable [] bytes) {
    try {
      return parser.parseFrom(bytes == null ? new byte[0] : bytes);
    } catch (InvalidProtocolBufferException e) {
      throw new IllegalStateException("ledger holds an unreadable response", e);
    }
  }

  static String digest(Message request) {
    try {
      MessageDigest sha = MessageDigest.getInstance("SHA-256");
      return HexFormat.of()
          .formatHex(sha.digest(Canonical.of(request).getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
