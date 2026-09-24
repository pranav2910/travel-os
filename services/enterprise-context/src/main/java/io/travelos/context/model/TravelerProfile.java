package io.travelos.context.model;

import io.travelos.common.tenant.TenantId;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Who a traveler is for the suppliers: names as on their documents, contact, date of birth,
 * nationality, preferences and loyalty memberships. Employees get a profile the first time someone
 * (they, an arranger, a travel admin) writes one; guests are created by a sponsor. The sensitive
 * values are decrypted into this record only when the caller may see them; {@link #toString} never
 * prints them.
 */
public record TravelerProfile(
    TenantId tenant,
    String travelerId,
    Kind kind,
    String givenName,
    String familyName,
    @Nullable String middleName,
    String email,
    @Nullable String phone,
    @Nullable LocalDate dateOfBirth,
    @Nullable String gender,
    @Nullable String nationality,
    @Nullable String homeAirport,
    Map<String, Object> preferences,
    List<Loyalty> loyalty,
    @Nullable EmergencyContact emergencyContact,
    @Nullable String sponsorEmployeeId,
    boolean active,
    long version,
    Instant createdAt,
    Instant updatedAt) {

  public enum Kind {
    EMPLOYEE,
    GUEST
  }

  /** A loyalty membership; the number is a secret shown only to those who may book with it. */
  public record Loyalty(String program, @Nullable String memberNumber, @Nullable String last4) {
    @Override
    public String toString() {
      return "Loyalty[" + program + " " + (last4 == null ? "" : last4) + "]";
    }
  }

  public record EmergencyContact(String name, String phone, @Nullable String relation) {
    @Override
    public String toString() {
      return "EmergencyContact[" + name + "]";
    }
  }

  public TravelerProfile {
    preferences = preferences == null ? Map.of() : Map.copyOf(preferences);
    loyalty = loyalty == null ? List.of() : List.copyOf(loyalty);
  }

  @Override
  public String toString() {
    return "TravelerProfile["
        + travelerId
        + " "
        + kind
        + " "
        + givenName
        + " "
        + familyName
        + " v"
        + version
        + (active ? "" : " inactive")
        + "]";
  }
}
