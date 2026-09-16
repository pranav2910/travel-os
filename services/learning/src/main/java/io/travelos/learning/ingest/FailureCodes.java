package io.travelos.learning.ingest;

import java.util.Set;

/**
 * Separates supplier quality from platform failures. A booking that failed because the supplier
 * gateway timed out, the transport was unavailable, the platform retried out or the payment was
 * declined says nothing about the supplier; one the supplier itself refused (seat, room or vehicle
 * gone, offer expired, cancellation refused) does. Unknown codes count as supplier failures only
 * when they are not shaped like a transport status.
 */
public final class FailureCodes {
  private static final Set<String> PLATFORM =
      Set.of(
          "TIMEOUT",
          "UNAVAILABLE",
          "RATE_LIMITED",
          "PAYMENT_DECLINED",
          "IDEMPOTENCY_KEY_REQUIRED",
          "PROVIDER_UNKNOWN",
          "PROVIDER_KIND_MISMATCH",
          "INTERNAL",
          "COMPENSATION_INCOMPLETE",
          "COMPENSATION_FAILED",
          "NO_CONFIRMED_ITEM",
          "CHANGE_NOT_SUPPORTED");

  private FailureCodes() {}

  public static boolean isPlatform(String code) {
    if (code == null || code.isBlank()) {
      return true;
    }
    // "SUPPLIER_<grpc status>" is how the Order service names a transport-level failure of the
    // supplier gateway call (SUPPLIER_UNAVAILABLE, SUPPLIER_DEADLINE_EXCEEDED ...).
    return PLATFORM.contains(code) || code.startsWith("SUPPLIER_");
  }
}
