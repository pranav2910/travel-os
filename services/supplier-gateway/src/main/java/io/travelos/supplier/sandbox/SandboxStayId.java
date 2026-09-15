package io.travelos.supplier.sandbox;

import io.travelos.supplier.AirSupplier.SupplierException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Base64;

/** Self-describing hotel offer id: everything needed to re-quote and book is inside it. */
record SandboxStayId(
    String city,
    LocalDate checkIn,
    LocalDate checkOut,
    String propertyCode,
    long issuedEpochSeconds) {
  static final String PREFIX = "SBH-";
  private static final String VERSION = "1";

  String encode() {
    String raw =
        String.join(
            "|",
            VERSION,
            city,
            checkIn.toString(),
            checkOut.toString(),
            propertyCode,
            Long.toString(issuedEpochSeconds));
    return PREFIX
        + Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
  }

  static SandboxStayId decode(String providerOfferId) {
    if (providerOfferId == null || !providerOfferId.startsWith(PREFIX)) {
      throw new SupplierException(
          "OFFER_UNKNOWN", "not a sandbox hotel offer id: " + providerOfferId, false);
    }
    try {
      String raw =
          new String(
              Base64.getUrlDecoder().decode(providerOfferId.substring(PREFIX.length())),
              StandardCharsets.UTF_8);
      String[] parts = raw.split("\\|", -1);
      if (parts.length != 6 || !VERSION.equals(parts[0])) {
        throw new IllegalArgumentException("wrong shape");
      }
      return new SandboxStayId(
          parts[1],
          LocalDate.parse(parts[2]),
          LocalDate.parse(parts[3]),
          parts[4],
          Long.parseLong(parts[5]));
    } catch (RuntimeException e) {
      throw new SupplierException(
          "OFFER_UNKNOWN", "unreadable sandbox hotel offer id: " + providerOfferId, false);
    }
  }
}
