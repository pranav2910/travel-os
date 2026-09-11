package io.travelos.supplier.sandbox;

import io.travelos.supplier.AirSupplier.SupplierException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Base64;
import org.jspecify.annotations.Nullable;

/**
 * The sandbox's provider_offer_id is self-describing: everything needed to regenerate the offer is
 * encoded in it, so pricing and booking need no search-session storage. Real suppliers hand out
 * opaque ids with an expiry; ours also expire, via the timestamp inside.
 *
 * @param slot index into the deterministic schedule for the route/date
 */
record SandboxOfferId(
    String origin,
    String destination,
    LocalDate outboundDate,
    @Nullable LocalDate inboundDate,
    int slot,
    String cabin,
    long issuedEpochSeconds) {

  private static final String VERSION = "1";

  String encode() {
    String raw =
        String.join(
            "|",
            VERSION,
            origin,
            destination,
            outboundDate.toString(),
            inboundDate == null ? "-" : inboundDate.toString(),
            Integer.toString(slot),
            cabin,
            Long.toString(issuedEpochSeconds));
    return "SBX-"
        + Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
  }

  static SandboxOfferId decode(String providerOfferId) {
    if (providerOfferId == null || !providerOfferId.startsWith("SBX-")) {
      throw new SupplierException(
          "OFFER_UNKNOWN", "not a sandbox offer id: " + providerOfferId, false);
    }
    String raw;
    try {
      raw =
          new String(
              Base64.getUrlDecoder().decode(providerOfferId.substring(4)), StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      throw new SupplierException("OFFER_UNKNOWN", "malformed sandbox offer id", false);
    }
    String[] parts = raw.split("\\|", -1);
    if (parts.length != 8 || !VERSION.equals(parts[0])) {
      throw new SupplierException("OFFER_UNKNOWN", "unsupported sandbox offer id", false);
    }
    try {
      return new SandboxOfferId(
          parts[1],
          parts[2],
          LocalDate.parse(parts[3]),
          "-".equals(parts[4]) ? null : LocalDate.parse(parts[4]),
          Integer.parseInt(parts[5]),
          parts[6],
          Long.parseLong(parts[7]));
    } catch (RuntimeException e) {
      throw new SupplierException("OFFER_UNKNOWN", "malformed sandbox offer id", false);
    }
  }
}
