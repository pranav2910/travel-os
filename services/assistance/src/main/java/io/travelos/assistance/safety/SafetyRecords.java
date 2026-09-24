package io.travelos.assistance.safety;

import io.travelos.common.tenant.TenantId;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Phase 8: traveler safety records. */
public final class SafetyRecords {
  private SafetyRecords() {}

  public enum Severity {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL;

    public boolean needsCheckin() {
      return this == HIGH || this == CRITICAL;
    }
  }

  public enum CheckinStatus {
    SAFE,
    NEEDS_HELP
  }

  public record Advisory(
      String advisoryId,
      TenantId tenant,
      String title,
      Severity severity,
      List<String> countries,
      List<String> cities,
      Instant startsAt,
      Instant endsAt,
      String text,
      @Nullable String source,
      boolean active,
      String issuedBy,
      Instant issuedAt,
      @Nullable Instant checkinDueAt) {
    public Advisory {
      countries = countries == null ? List.of() : List.copyOf(countries);
      cities = cities == null ? List.of() : List.copyOf(cities);
    }
  }

  public record Affected(
      String advisoryId,
      TenantId tenant,
      String travelerId,
      String tripId,
      Instant notifiedAt,
      @Nullable String caseId) {}

  public record Checkin(
      String checkinId,
      String advisoryId,
      TenantId tenant,
      String travelerId,
      CheckinStatus status,
      @Nullable String note,
      Instant recordedAt) {}
}
