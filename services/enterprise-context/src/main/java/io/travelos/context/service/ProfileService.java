package io.travelos.context.service;

import io.travelos.common.crypto.FieldCipher;
import io.travelos.common.ids.IdPrefix;
import io.travelos.common.ids.Ids;
import io.travelos.common.tenant.TenantId;
import io.travelos.context.ProfileProperties;
import io.travelos.context.model.TravelDocument;
import io.travelos.context.model.TravelerProfile;
import io.travelos.context.service.ProfileAccess.Caller;
import io.travelos.context.service.ProfileAccess.Relation;
import io.travelos.context.store.ArrangerRepository;
import io.travelos.context.store.EmployeeRepository;
import io.travelos.context.store.ProfileRepository;
import io.travelos.spring.web.auth.RequestPrincipal;
import io.travelos.spring.web.error.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Traveler profiles, guests and travel documents. Reads are shaped by the caller's relationship to
 * the traveler; every disclosure of a decrypted value is logged with who, what and why.
 */
@Service
public class ProfileService {
  private static final Logger log = LoggerFactory.getLogger(ProfileService.class);

  private final ProfileRepository profiles;
  private final EmployeeRepository employees;
  private final ArrangerRepository grants;
  private final ProfileAccess access;
  private final ProfileProperties properties;
  private final Clock clock;

  public ProfileService(
      ProfileRepository profiles,
      EmployeeRepository employees,
      ArrangerRepository grants,
      ProfileAccess access,
      ProfileProperties properties,
      Clock clock) {
    this.profiles = profiles;
    this.employees = employees;
    this.grants = grants;
    this.access = access;
    this.properties = properties;
    this.clock = clock;
  }

  public record ProfileUpdate(
      @Nullable String givenName,
      @Nullable String familyName,
      @Nullable String middleName,
      @Nullable String email,
      @Nullable String phone,
      @Nullable LocalDate dateOfBirth,
      @Nullable String gender,
      @Nullable String nationality,
      @Nullable String homeAirport,
      @Nullable Map<String, Object> preferences,
      @Nullable List<TravelerProfile.Loyalty> loyalty,
      TravelerProfile.@Nullable EmergencyContact emergencyContact,
      @Nullable Long expectedVersion) {}

  /** What a caller sees: the profile shaped by the relationship, and the relationship itself. */
  public record View(TravelerProfile profile, Relation relation) {}

  public static Caller caller(RequestPrincipal me) {
    return new Caller(me.employeeId(), me.roles());
  }

  // ------------------------------------------------------------------ reads

  /**
   * The profile, decrypted to the extent the relationship allows. {@code purpose} is written to the
   * access log whenever a sensitive value is disclosed.
   */
  public View get(RequestPrincipal me, String travelerId, boolean reveal, String purpose) {
    return get(me.tenant(), caller(me), me.principal().id(), travelerId, reveal, purpose);
  }

  public View get(
      TenantId tenant,
      Caller me,
      String principalId,
      String travelerId,
      boolean reveal,
      String purpose) {
    Relation relation = access.relation(tenant, me, travelerId);
    if (!ProfileAccess.mayReadProfile(relation)) {
      throw new ApiException.NotFound("traveler", travelerId);
    }
    boolean disclose = reveal && ProfileAccess.mayRevealSensitive(relation);
    TravelerProfile profile =
        profiles
            .find(tenant, travelerId, disclose)
            .or(() -> synthesized(tenant, travelerId))
            .orElseThrow(() -> new ApiException.NotFound("traveler", travelerId));
    if (disclose) {
      profiles.logSensitiveAccess(
          tenant, principalId, travelerId, "PROFILE", purpose, clock.instant());
    }
    return new View(profile, relation);
  }

  /** An employee without a stored profile still has a name and an email: the HRIS record's. */
  private Optional<TravelerProfile> synthesized(TenantId tenant, String travelerId) {
    return employees
        .find(tenant, travelerId)
        .map(
            e -> {
              String[] names = splitName(e.displayName());
              Instant now = clock.instant();
              return new TravelerProfile(
                  tenant,
                  travelerId,
                  TravelerProfile.Kind.EMPLOYEE,
                  names[0],
                  names[1],
                  null,
                  e.email(),
                  null,
                  null,
                  null,
                  null,
                  null,
                  Map.of(),
                  List.of(),
                  null,
                  null,
                  e.active(),
                  0,
                  now,
                  now);
            });
  }

  static String[] splitName(String displayName) {
    String trimmed = displayName == null ? "" : displayName.trim();
    int space = trimmed.lastIndexOf(' ');
    if (space < 0) {
      return new String[] {trimmed, trimmed};
    }
    return new String[] {trimmed.substring(0, space).trim(), trimmed.substring(space + 1).trim()};
  }

  public List<TravelerProfile> guestsOf(RequestPrincipal me) {
    return profiles.guestsOf(me.tenant(), me.employeeIdOrThrow());
  }

  public List<Map<String, Object>> changes(RequestPrincipal me, String travelerId) {
    requireRelation(me, travelerId, ProfileAccess::mayReadProfile);
    return profiles.changes(me.tenant(), travelerId);
  }

  public List<Map<String, Object>> sensitiveAccess(RequestPrincipal me, String travelerId) {
    Relation r = access.relation(me.tenant(), caller(me), travelerId);
    if (r != Relation.SELF && r != Relation.TRAVEL_ADMIN) {
      throw new ApiException.NotFound("traveler", travelerId);
    }
    return profiles.sensitiveAccess(me.tenant(), travelerId, 200);
  }

  // ------------------------------------------------------------------ writes

  @Transactional
  public View update(RequestPrincipal me, String travelerId, ProfileUpdate u) {
    Relation relation = requireRelation(me, travelerId, ProfileAccess::mayWrite);
    TenantId tenant = me.tenant();
    Instant now = clock.instant();
    Optional<TravelerProfile> stored = profiles.find(tenant, travelerId, true);
    TravelerProfile base =
        stored
            .or(() -> synthesized(tenant, travelerId))
            .orElseThrow(() -> new ApiException.NotFound("traveler", travelerId));
    if (!base.active()) {
      throw new ApiException.Conflict("TRAVELER_INACTIVE", travelerId + " is deactivated");
    }
    if (u.expectedVersion() != null && u.expectedVersion() != base.version()) {
      throw new ApiException.Conflict(
          "PROFILE_VERSION_STALE",
          "profile is at version " + base.version() + ", not " + u.expectedVersion());
    }
    List<String> changed = new ArrayList<>();
    TravelerProfile next =
        new TravelerProfile(
            tenant,
            travelerId,
            base.kind(),
            pick(changed, "givenName", base.givenName(), u.givenName()),
            pick(changed, "familyName", base.familyName(), u.familyName()),
            pick(changed, "middleName", base.middleName(), u.middleName()),
            pick(changed, "email", base.email(), u.email()),
            pick(changed, "phone", base.phone(), u.phone()),
            pick(changed, "dateOfBirth", base.dateOfBirth(), u.dateOfBirth()),
            pick(changed, "gender", base.gender(), u.gender()),
            pick(changed, "nationality", base.nationality(), u.nationality()),
            pick(changed, "homeAirport", base.homeAirport(), u.homeAirport()),
            pick(changed, "preferences", base.preferences(), u.preferences()),
            pick(changed, "loyalty", base.loyalty(), u.loyalty()),
            pick(changed, "emergencyContact", base.emergencyContact(), u.emergencyContact()),
            base.sponsorEmployeeId(),
            true,
            base.version() + 1,
            stored.map(TravelerProfile::createdAt).orElse(now),
            now);
    if (changed.isEmpty() && stored.isPresent()) {
      return new View(stored.get(), relation);
    }
    validate(next);
    profiles.upsert(next);
    profiles.recordChange(tenant, travelerId, next.version(), me.principal().id(), now, changed);
    log.info(
        "profile {} v{} changed by {} ({}): {}",
        travelerId,
        next.version(),
        me.principal().id(),
        relation,
        changed);
    // The writer sees what they wrote; the record was decrypted for the merge.
    return new View(next, relation);
  }

  private static <T> T pick(List<String> changed, String field, T current, @Nullable T proposed) {
    if (proposed == null || Objects.equals(current, proposed)) {
      return current;
    }
    changed.add(field);
    return proposed;
  }

  private static void validate(TravelerProfile p) {
    if (p.givenName().isBlank() || p.familyName().isBlank()) {
      throw new ApiException.Unprocessable("NAME_REQUIRED", "given and family names are required");
    }
    if (p.email().isBlank() || !p.email().contains("@")) {
      throw new ApiException.Unprocessable("EMAIL_INVALID", "email is not an address");
    }
    if (p.nationality() != null && !p.nationality().matches("^[A-Z]{2}$")) {
      throw new ApiException.Unprocessable(
          "NATIONALITY_INVALID", "nationality is ISO 3166-1 alpha-2");
    }
    if (p.homeAirport() != null && !p.homeAirport().matches("^[A-Z]{3}$")) {
      throw new ApiException.Unprocessable("HOME_AIRPORT_INVALID", "home airport is an IATA code");
    }
    for (TravelerProfile.Loyalty l : p.loyalty()) {
      if (l.program() == null || l.program().isBlank() || l.memberNumber() == null) {
        throw new ApiException.Unprocessable("LOYALTY_INVALID", "loyalty needs program and number");
      }
    }
  }

  /** A guest traveler, sponsored (and arranged for) by the employee who creates them. */
  @Transactional
  public TravelerProfile createGuest(RequestPrincipal me, ProfileUpdate u) {
    String sponsor = me.employeeIdOrThrow();
    Instant now = clock.instant();
    TravelerProfile guest =
        new TravelerProfile(
            me.tenant(),
            Ids.newId(IdPrefix.GUEST),
            TravelerProfile.Kind.GUEST,
            Objects.requireNonNullElse(u.givenName(), ""),
            Objects.requireNonNullElse(u.familyName(), ""),
            u.middleName(),
            Objects.requireNonNullElse(u.email(), ""),
            u.phone(),
            u.dateOfBirth(),
            u.gender(),
            u.nationality(),
            u.homeAirport(),
            u.preferences() == null ? Map.of() : u.preferences(),
            u.loyalty() == null ? List.of() : u.loyalty(),
            u.emergencyContact(),
            sponsor,
            true,
            1,
            now,
            now);
    validate(guest);
    profiles.upsert(guest);
    profiles.recordChange(
        me.tenant(), guest.travelerId(), 1, me.principal().id(), now, List.of("created"));
    log.info("guest {} created by {}", guest.travelerId(), me.principal().id());
    return guest;
  }

  // ------------------------------------------------------------------ documents

  public record DocumentInput(
      TravelDocument.Type type,
      String number,
      String issuingCountry,
      @Nullable String nationality,
      @Nullable LocalDate issuedOn,
      LocalDate expiresOn,
      @Nullable String holderGivenName,
      @Nullable String holderFamilyName) {}

  public List<TravelDocument> documents(RequestPrincipal me, String travelerId) {
    requireRelation(me, travelerId, ProfileAccess::mayReadProfile);
    return profiles.documents(me.tenant(), travelerId, false);
  }

  @Transactional
  public TravelDocument addDocument(RequestPrincipal me, String travelerId, DocumentInput in) {
    requireRelation(me, travelerId, ProfileAccess::mayWrite);
    TravelerProfile p = get(me, travelerId, false, "DOCUMENT_ADD").profile();
    if (in.number() == null || in.number().isBlank() || in.number().length() > 40) {
      throw new ApiException.Unprocessable("DOCUMENT_NUMBER_INVALID", "document number required");
    }
    if (!in.issuingCountry().matches("^[A-Z]{2}$")) {
      throw new ApiException.Unprocessable("COUNTRY_INVALID", "issuing country is ISO alpha-2");
    }
    LocalDate today = LocalDate.now(clock);
    if (!in.expiresOn().isAfter(today)) {
      throw new ApiException.Unprocessable("DOCUMENT_EXPIRED", "document already expired");
    }
    Instant now = clock.instant();
    String number = in.number().replace(" ", "").toUpperCase();
    TravelDocument d =
        new TravelDocument(
            me.tenant(),
            Ids.newId(IdPrefix.DOCUMENT),
            travelerId,
            in.type(),
            number,
            FieldCipher.last4(number),
            in.issuingCountry(),
            in.nationality() == null ? p.nationality() : in.nationality(),
            in.issuedOn(),
            in.expiresOn(),
            in.holderGivenName() == null ? p.givenName() : in.holderGivenName(),
            in.holderFamilyName() == null ? p.familyName() : in.holderFamilyName(),
            1,
            now,
            now,
            null,
            in.expiresOn().plusDays(properties.documentRetentionDays()));
    profiles.insertDocument(d);
    profiles.recordChange(
        me.tenant(),
        travelerId,
        0,
        me.principal().id(),
        now,
        List.of("document:" + d.documentId()));
    return d.withoutNumber();
  }

  /** The decrypted number, only for those who may book with it; always logged. */
  public TravelDocument revealDocument(RequestPrincipal me, String documentId, String purpose) {
    TravelDocument masked =
        profiles
            .document(me.tenant(), documentId, false)
            .orElseThrow(() -> new ApiException.NotFound("document", documentId));
    Relation r = access.relation(me.tenant(), caller(me), masked.travelerId());
    if (!ProfileAccess.mayReadProfile(r)) {
      throw new ApiException.NotFound("document", documentId);
    }
    if (!ProfileAccess.mayRevealDocuments(r)) {
      throw new ApiException.Forbidden(
          "DOCUMENT_ACCESS_DENIED", "reading document numbers needs a grant that allows it");
    }
    if (masked.revoked()) {
      throw new ApiException.Conflict("DOCUMENT_REVOKED", documentId + " was revoked");
    }
    TravelDocument full = profiles.document(me.tenant(), documentId, true).orElseThrow();
    profiles.logSensitiveAccess(
        me.tenant(),
        me.principal().id(),
        masked.travelerId(),
        "DOCUMENT:" + documentId,
        purpose,
        clock.instant());
    return full;
  }

  @Transactional
  public void revokeDocument(RequestPrincipal me, String documentId) {
    TravelDocument d =
        profiles
            .document(me.tenant(), documentId, false)
            .orElseThrow(() -> new ApiException.NotFound("document", documentId));
    requireRelation(me, d.travelerId(), ProfileAccess::mayWrite);
    Instant now = clock.instant();
    profiles.revokeDocument(
        me.tenant(),
        documentId,
        now,
        LocalDate.now(clock).plusDays(properties.documentRetentionDays()));
    profiles.recordChange(
        me.tenant(),
        d.travelerId(),
        0,
        me.principal().id(),
        now,
        List.of("document-revoked:" + documentId));
  }

  // ------------------------------------------------------------------ lifecycle

  public record Deactivation(
      String employeeId,
      boolean employeeDeactivated,
      boolean profileDeactivated,
      int grantsRevoked,
      int documentsRevoked) {}

  /** Offboarding: the employee cannot travel or arrange, and their documents enter retention. */
  @Transactional
  public Deactivation deactivate(RequestPrincipal me, String travelerId) {
    if (!me.hasRole("TRAVEL_ADMIN")) {
      throw new ApiException.Forbidden(
          "TRAVEL_ADMIN_REQUIRED", "deactivation is a travel admin action");
    }
    TenantId tenant = me.tenant();
    Instant now = clock.instant();
    boolean employee = employees.deactivate(tenant, travelerId, now);
    boolean profile = profiles.deactivate(tenant, travelerId, now);
    if (!employee && !profile) {
      throw new ApiException.NotFound("traveler", travelerId);
    }
    int grantsRevoked = grants.revokeAllOf(tenant, travelerId, now);
    int docs =
        profiles.revokeDocumentsOf(
            tenant,
            travelerId,
            now,
            LocalDate.now(clock).plusDays(properties.documentRetentionDays()));
    profiles.recordChange(tenant, travelerId, 0, me.principal().id(), now, List.of("deactivated"));
    log.info(
        "traveler {} deactivated by {}: grants revoked {}, documents revoked {}",
        travelerId,
        me.principal().id(),
        grantsRevoked,
        docs);
    return new Deactivation(travelerId, employee, profile, grantsRevoked, docs);
  }

  /** Retention: revoked and expired documents are deleted once their retention date passes. */
  @Scheduled(fixedDelayString = "${travelos.profiles.retention-sweep:1h}")
  public void purgeExpiredDocuments() {
    int purged = profiles.purgeExpired(LocalDate.now(clock));
    if (purged > 0) {
      log.info("retention purge deleted {} travel documents", purged);
    }
  }

  public int purgeExpiredDocumentsNow(LocalDate today) {
    return profiles.purgeExpired(today);
  }

  private Relation requireRelation(
      RequestPrincipal me, String travelerId, java.util.function.Predicate<Relation> allowed) {
    Relation r = access.relation(me.tenant(), caller(me), travelerId);
    if (!ProfileAccess.mayReadProfile(r)) {
      throw new ApiException.NotFound("traveler", travelerId);
    }
    if (!allowed.test(r)) {
      throw new ApiException.Forbidden(
          "PROFILE_ACCESS_DENIED",
          "your relationship to " + travelerId + " (" + r + ") does not allow this");
    }
    return r;
  }

  static LocalDate today(Clock clock) {
    return clock.instant().atZone(ZoneOffset.UTC).toLocalDate();
  }
}
