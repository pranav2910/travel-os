package io.travelos.context.model;

import io.travelos.common.tenant.TenantId;
import java.time.Instant;
import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/**
 * A passport, national id or visa. The number is held encrypted and decrypted into {@code number}
 * only for a caller who may see it (every such read is logged); everyone else gets the last four
 * characters. {@link #toString} never prints the number.
 */
public record TravelDocument(
    TenantId tenant,
    String documentId,
    String travelerId,
    Type type,
    @Nullable String number,
    String numberLast4,
    String issuingCountry,
    @Nullable String nationality,
    @Nullable LocalDate issuedOn,
    LocalDate expiresOn,
    String holderGivenName,
    String holderFamilyName,
    long version,
    Instant createdAt,
    Instant updatedAt,
    @Nullable Instant revokedAt,
    LocalDate retentionUntil) {

  public enum Type {
    PASSPORT,
    NATIONAL_ID,
    VISA
  }

  public boolean revoked() {
    return revokedAt != null;
  }

  public TravelDocument withoutNumber() {
    return new TravelDocument(
        tenant,
        documentId,
        travelerId,
        type,
        null,
        numberLast4,
        issuingCountry,
        nationality,
        issuedOn,
        expiresOn,
        holderGivenName,
        holderFamilyName,
        version,
        createdAt,
        updatedAt,
        revokedAt,
        retentionUntil);
  }

  @Override
  public String toString() {
    return "TravelDocument["
        + documentId
        + " "
        + type
        + " "
        + numberLast4
        + " exp "
        + expiresOn
        + "]";
  }
}
