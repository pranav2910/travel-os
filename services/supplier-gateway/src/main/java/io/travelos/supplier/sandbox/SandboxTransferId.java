package io.travelos.supplier.sandbox;

import io.travelos.supplier.AirSupplier.SupplierException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Self-describing ground offer id: city, kind, route, pickup instant, vendor, issue time. */
record SandboxTransferId(
    String city,
    String kind,
    String from,
    String to,
    long pickupEpochSeconds,
    String vendorCode,
    long issuedEpochSeconds) {
  static final String PREFIX = "SBG-";
  private static final String VERSION = "1";

  String encode() {
    String raw =
        String.join(
            "|",
            VERSION,
            city,
            kind,
            from,
            to,
            Long.toString(pickupEpochSeconds),
            vendorCode,
            Long.toString(issuedEpochSeconds));
    return PREFIX
        + Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
  }

  static SandboxTransferId decode(String providerOfferId) {
    if (providerOfferId == null || !providerOfferId.startsWith(PREFIX)) {
      throw new SupplierException(
          "OFFER_UNKNOWN", "not a sandbox ground offer id: " + providerOfferId, false);
    }
    try {
      String raw =
          new String(
              Base64.getUrlDecoder().decode(providerOfferId.substring(PREFIX.length())),
              StandardCharsets.UTF_8);
      String[] parts = raw.split("\\|", -1);
      if (parts.length != 8 || !VERSION.equals(parts[0])) {
        throw new IllegalArgumentException("wrong shape");
      }
      return new SandboxTransferId(
          parts[1],
          parts[2],
          parts[3],
          parts[4],
          Long.parseLong(parts[5]),
          parts[6],
          Long.parseLong(parts[7]));
    } catch (RuntimeException e) {
      throw new SupplierException(
          "OFFER_UNKNOWN", "unreadable sandbox ground offer id: " + providerOfferId, false);
    }
  }
}
