package io.travelos.context.store;

import io.travelos.common.crypto.FieldCipher;
import io.travelos.common.tenant.TenantId;
import io.travelos.context.model.TravelDocument;
import io.travelos.context.model.TravelerProfile;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Profiles and documents. Encryption happens here, at the boundary with the database: the rows hold
 * ciphertext, the records hold plaintext only when {@code reveal} asked for it.
 */
@Repository
public class ProfileRepository {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private final JdbcClient jdbc;
  private final FieldCipher cipher;

  public ProfileRepository(JdbcClient jdbc, FieldCipher cipher) {
    this.jdbc = jdbc;
    this.cipher = cipher;
  }

  public void upsert(TravelerProfile p) {
    jdbc.sql(
            """
            INSERT INTO traveler_profile (tenant_id, traveler_id, kind, given_name, family_name, middle_name, email,
              phone_enc, date_of_birth_enc, gender, nationality, home_airport, preferences, loyalty_enc, emergency_enc,
              sponsor_employee_id, active, version, created_at, updated_at)
            VALUES (:t, :id, :kind, :given, :family, :middle, :email, :phone, :dob, :gender, :nationality, :home,
              CAST(:prefs AS jsonb), :loyalty, :emergency, :sponsor, :active, :version, :created, :updated)
            ON CONFLICT (tenant_id, traveler_id) DO UPDATE SET
              given_name = EXCLUDED.given_name, family_name = EXCLUDED.family_name, middle_name = EXCLUDED.middle_name,
              email = EXCLUDED.email, phone_enc = EXCLUDED.phone_enc, date_of_birth_enc = EXCLUDED.date_of_birth_enc,
              gender = EXCLUDED.gender, nationality = EXCLUDED.nationality, home_airport = EXCLUDED.home_airport,
              preferences = EXCLUDED.preferences, loyalty_enc = EXCLUDED.loyalty_enc, emergency_enc = EXCLUDED.emergency_enc,
              active = EXCLUDED.active, version = EXCLUDED.version, updated_at = EXCLUDED.updated_at
            WHERE traveler_profile.version = EXCLUDED.version - 1
            """)
        .param("t", p.tenant().value())
        .param("id", p.travelerId())
        .param("kind", p.kind().name())
        .param("given", p.givenName())
        .param("family", p.familyName())
        .param("middle", p.middleName())
        .param("email", p.email())
        .param("phone", cipher.encrypt(p.phone()))
        .param("dob", cipher.encrypt(p.dateOfBirth() == null ? null : p.dateOfBirth().toString()))
        .param("gender", p.gender())
        .param("nationality", p.nationality())
        .param("home", p.homeAirport())
        .param("prefs", JSON.writeValueAsString(p.preferences()))
        .param(
            "loyalty",
            p.loyalty().isEmpty() ? null : cipher.encrypt(JSON.writeValueAsString(p.loyalty())))
        .param(
            "emergency",
            p.emergencyContact() == null
                ? null
                : cipher.encrypt(JSON.writeValueAsString(p.emergencyContact())))
        .param("sponsor", p.sponsorEmployeeId())
        .param("active", p.active())
        .param("version", p.version())
        .param("created", Rows.ts(p.createdAt()))
        .param("updated", Rows.ts(p.updatedAt()))
        .update();
  }

  /** The stored version, to refuse a stale update before writing. */
  public Optional<Long> version(TenantId tenant, String travelerId) {
    return jdbc.sql(
            "SELECT version FROM traveler_profile WHERE tenant_id = :t AND traveler_id = :id")
        .param("t", tenant.value())
        .param("id", travelerId)
        .query(Long.class)
        .optional();
  }

  /**
   * @param reveal decrypt phone, date of birth, loyalty numbers and the emergency contact; the
   *     caller has checked who is asking and logs it
   */
  public Optional<TravelerProfile> find(TenantId tenant, String travelerId, boolean reveal) {
    return jdbc.sql("SELECT * FROM traveler_profile WHERE tenant_id = :t AND traveler_id = :id")
        .param("t", tenant.value())
        .param("id", travelerId)
        .query((rs, i) -> map(rs, reveal))
        .optional();
  }

  public List<TravelerProfile> guestsOf(TenantId tenant, String sponsorEmployeeId) {
    return jdbc.sql(
            "SELECT * FROM traveler_profile WHERE tenant_id = :t AND sponsor_employee_id = :s ORDER BY family_name, given_name")
        .param("t", tenant.value())
        .param("s", sponsorEmployeeId)
        .query((rs, i) -> map(rs, false))
        .list();
  }

  public boolean deactivate(TenantId tenant, String travelerId, Instant now) {
    return jdbc.sql(
                "UPDATE traveler_profile SET active = FALSE, version = version + 1, updated_at = :now WHERE tenant_id = :t AND traveler_id = :id AND active")
            .param("now", Rows.ts(now))
            .param("t", tenant.value())
            .param("id", travelerId)
            .update()
        == 1;
  }

  public void recordChange(
      TenantId tenant,
      String travelerId,
      long version,
      String changedBy,
      Instant at,
      List<String> fields) {
    jdbc.sql(
            """
            INSERT INTO profile_change (tenant_id, traveler_id, version, changed_by, changed_at, fields)
            VALUES (:t, :id, :v, :by, :at, CAST(:fields AS jsonb))
            """)
        .param("t", tenant.value())
        .param("id", travelerId)
        .param("v", version)
        .param("by", changedBy)
        .param("at", Rows.ts(at))
        .param("fields", JSON.writeValueAsString(fields))
        .update();
  }

  public List<Map<String, Object>> changes(TenantId tenant, String travelerId) {
    return jdbc.sql(
            "SELECT version, changed_by, changed_at, fields::text AS fields FROM profile_change WHERE tenant_id = :t AND traveler_id = :id ORDER BY version")
        .param("t", tenant.value())
        .param("id", travelerId)
        .query(
            (rs, i) ->
                Map.<String, Object>of(
                    "version", rs.getLong("version"),
                    "changedBy", rs.getString("changed_by"),
                    "changedAt",
                        rs.getObject("changed_at", OffsetDateTime.class).toInstant().toString(),
                    "fields",
                        JSON.readValue(
                            rs.getString("fields"), new TypeReference<List<String>>() {})))
        .list();
  }

  // ------------------------------------------------------------------ documents

  public void insertDocument(TravelDocument d) {
    jdbc.sql(
            """
            INSERT INTO travel_document (tenant_id, document_id, traveler_id, type, number_enc, number_last4, issuing_country,
              nationality, issued_on, expires_on, holder_given_name, holder_family_name, version, created_at, updated_at,
              revoked_at, retention_until)
            VALUES (:t, :id, :traveler, :type, :number, :last4, :country, :nationality, :issued, :expires, :hg, :hf, 1,
              :now, :now, NULL, :retention)
            """)
        .param("t", d.tenant().value())
        .param("id", d.documentId())
        .param("traveler", d.travelerId())
        .param("type", d.type().name())
        .param("number", cipher.encrypt(d.number()))
        .param("last4", d.numberLast4())
        .param("country", d.issuingCountry())
        .param("nationality", d.nationality())
        .param("issued", Rows.date(d.issuedOn()))
        .param("expires", Rows.date(d.expiresOn()))
        .param("hg", d.holderGivenName())
        .param("hf", d.holderFamilyName())
        .param("now", Rows.ts(d.createdAt()))
        .param("retention", Rows.date(d.retentionUntil()))
        .update();
  }

  public List<TravelDocument> documents(TenantId tenant, String travelerId, boolean reveal) {
    return jdbc.sql(
            "SELECT * FROM travel_document WHERE tenant_id = :t AND traveler_id = :id AND revoked_at IS NULL ORDER BY type, expires_on DESC")
        .param("t", tenant.value())
        .param("id", travelerId)
        .query((rs, i) -> mapDocument(rs, reveal))
        .list();
  }

  public Optional<TravelDocument> document(TenantId tenant, String documentId, boolean reveal) {
    return jdbc.sql("SELECT * FROM travel_document WHERE tenant_id = :t AND document_id = :id")
        .param("t", tenant.value())
        .param("id", documentId)
        .query((rs, i) -> mapDocument(rs, reveal))
        .optional();
  }

  public boolean revokeDocument(
      TenantId tenant, String documentId, Instant now, LocalDate retentionUntil) {
    return jdbc.sql(
                "UPDATE travel_document SET revoked_at = :now, retention_until = :ret, updated_at = :now, version = version + 1 WHERE tenant_id = :t AND document_id = :id AND revoked_at IS NULL")
            .param("now", Rows.ts(now))
            .param("ret", Rows.date(retentionUntil))
            .param("t", tenant.value())
            .param("id", documentId)
            .update()
        == 1;
  }

  public int revokeDocumentsOf(
      TenantId tenant, String travelerId, Instant now, LocalDate retentionUntil) {
    return jdbc.sql(
            "UPDATE travel_document SET revoked_at = :now, retention_until = :ret, updated_at = :now, version = version + 1 WHERE tenant_id = :t AND traveler_id = :tr AND revoked_at IS NULL")
        .param("now", Rows.ts(now))
        .param("ret", Rows.date(retentionUntil))
        .param("t", tenant.value())
        .param("tr", travelerId)
        .update();
  }

  /** Retention: rows past their retention date are deleted, ciphertext and all. */
  public int purgeExpired(LocalDate today) {
    return jdbc.sql("DELETE FROM travel_document WHERE retention_until < :today")
        .param("today", Rows.date(today))
        .update();
  }

  public void logSensitiveAccess(
      TenantId tenant,
      String principal,
      String travelerId,
      String kind,
      String purpose,
      Instant at) {
    jdbc.sql(
            "INSERT INTO sensitive_access (tenant_id, principal, traveler_id, kind, purpose, at) VALUES (:t, :p, :tr, :k, :purpose, :at)")
        .param("t", tenant.value())
        .param("p", principal)
        .param("tr", travelerId)
        .param("k", kind)
        .param("purpose", purpose)
        .param("at", Rows.ts(at))
        .update();
  }

  public List<Map<String, Object>> sensitiveAccess(TenantId tenant, String travelerId, int limit) {
    return jdbc.sql(
            "SELECT principal, kind, purpose, at FROM sensitive_access WHERE tenant_id = :t AND traveler_id = :id ORDER BY at DESC LIMIT :n")
        .param("t", tenant.value())
        .param("id", travelerId)
        .param("n", limit)
        .query(
            (rs, i) ->
                Map.<String, Object>of(
                    "principal", rs.getString("principal"),
                    "kind", rs.getString("kind"),
                    "purpose", rs.getString("purpose"),
                    "at", rs.getObject("at", OffsetDateTime.class).toInstant().toString()))
        .list();
  }

  private TravelerProfile map(ResultSet rs, boolean reveal) throws SQLException {
    String loyaltyEnc = rs.getString("loyalty_enc");
    List<TravelerProfile.Loyalty> loyalty = List.of();
    if (loyaltyEnc != null) {
      List<TravelerProfile.Loyalty> stored =
          JSON.readValue(
              cipher.decrypt(loyaltyEnc), new TypeReference<List<TravelerProfile.Loyalty>>() {});
      loyalty =
          stored.stream()
              .map(
                  l ->
                      new TravelerProfile.Loyalty(
                          l.program(),
                          reveal ? l.memberNumber() : null,
                          FieldCipher.last4(l.memberNumber())))
              .toList();
    }
    String emergencyEnc = rs.getString("emergency_enc");
    TravelerProfile.EmergencyContact emergency =
        emergencyEnc == null || !reveal
            ? null
            : JSON.readValue(cipher.decrypt(emergencyEnc), TravelerProfile.EmergencyContact.class);
    String dobEnc = rs.getString("date_of_birth_enc");
    return new TravelerProfile(
        TenantId.of(rs.getString("tenant_id")),
        rs.getString("traveler_id"),
        TravelerProfile.Kind.valueOf(rs.getString("kind")),
        rs.getString("given_name"),
        rs.getString("family_name"),
        rs.getString("middle_name"),
        rs.getString("email"),
        reveal ? cipher.decrypt(rs.getString("phone_enc")) : null,
        reveal && dobEnc != null ? LocalDate.parse(cipher.decrypt(dobEnc)) : null,
        rs.getString("gender"),
        rs.getString("nationality"),
        rs.getString("home_airport"),
        JSON.readValue(rs.getString("preferences"), new TypeReference<Map<String, Object>>() {}),
        loyalty,
        emergency,
        rs.getString("sponsor_employee_id"),
        rs.getBoolean("active"),
        rs.getLong("version"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        rs.getObject("updated_at", OffsetDateTime.class).toInstant());
  }

  private TravelDocument mapDocument(ResultSet rs, boolean reveal) throws SQLException {
    return new TravelDocument(
        TenantId.of(rs.getString("tenant_id")),
        rs.getString("document_id"),
        rs.getString("traveler_id"),
        TravelDocument.Type.valueOf(rs.getString("type")),
        reveal ? cipher.decrypt(rs.getString("number_enc")) : null,
        rs.getString("number_last4"),
        rs.getString("issuing_country"),
        rs.getString("nationality"),
        Rows.date(rs, "issued_on"),
        java.util.Objects.requireNonNull(Rows.date(rs, "expires_on")),
        rs.getString("holder_given_name"),
        rs.getString("holder_family_name"),
        rs.getLong("version"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        rs.getObject("updated_at", OffsetDateTime.class).toInstant(),
        Rows.instant(rs, "revoked_at"),
        java.util.Objects.requireNonNull(Rows.date(rs, "retention_until")));
  }

  static @Nullable String nullIfBlank(@Nullable String s) {
    return s == null || s.isBlank() ? null : s;
  }
}
