package io.travelos.context.api;

import io.travelos.context.model.TravelDocument;
import io.travelos.context.model.TravelerProfile;
import io.travelos.context.service.ProfileService;
import io.travelos.spring.web.auth.RequestPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Traveler profiles, guests and travel documents. What a caller gets back depends on their
 * relationship to the traveler (see {@code ProfileAccess}); sensitive values are redacted unless
 * {@code reveal=true} is asked for by someone allowed to see them, and every such read is logged.
 */
@RestController
@RequestMapping(path = "/api/v1/travelers", produces = "application/json")
public class TravelerController {
  private final ProfileService profiles;

  public TravelerController(ProfileService profiles) {
    this.profiles = profiles;
  }

  public record LoyaltyView(
      String program, @Nullable String memberNumber, @Nullable String memberNumberLast4) {}

  public record EmergencyView(String name, String phone, @Nullable String relation) {}

  public record ProfileView(
      String travelerId,
      String kind,
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
      List<LoyaltyView> loyalty,
      @Nullable EmergencyView emergencyContact,
      @Nullable String sponsorEmployeeId,
      boolean active,
      long version,
      /** Your relationship to this traveler: SELF, TRAVEL_ADMIN, SPONSOR, MANAGER, GRANT, … */
      String relation,
      boolean sensitiveRevealed,
      Instant updatedAt) {
    static ProfileView from(TravelerProfile p, String relation, boolean revealed) {
      return new ProfileView(
          p.travelerId(),
          p.kind().name(),
          p.givenName(),
          p.familyName(),
          p.middleName(),
          p.email(),
          p.phone(),
          p.dateOfBirth(),
          p.gender(),
          p.nationality(),
          p.homeAirport(),
          p.preferences(),
          p.loyalty().stream()
              .map(l -> new LoyaltyView(l.program(), l.memberNumber(), l.last4()))
              .toList(),
          p.emergencyContact() == null
              ? null
              : new EmergencyView(
                  p.emergencyContact().name(),
                  p.emergencyContact().phone(),
                  p.emergencyContact().relation()),
          p.sponsorEmployeeId(),
          p.active(),
          p.version(),
          relation,
          revealed,
          p.updatedAt());
    }
  }

  public record LoyaltyInput(
      @NotBlank @Size(max = 40) String program, @NotBlank @Size(max = 40) String memberNumber) {}

  public record EmergencyInput(
      @NotBlank @Size(max = 120) String name,
      @NotBlank @Size(max = 40) String phone,
      @Nullable @Size(max = 40) String relation) {}

  public record ProfileRequest(
      @Nullable @Size(max = 80) String givenName,
      @Nullable @Size(max = 80) String familyName,
      @Nullable @Size(max = 80) String middleName,
      @Nullable @Size(max = 200) String email,
      @Nullable @Size(max = 40) String phone,
      @Nullable LocalDate dateOfBirth,
      @Nullable @Pattern(regexp = "^(M|F|X)$") String gender,
      @Nullable @Pattern(regexp = "^[A-Z]{2}$") String nationality,
      @Nullable @Pattern(regexp = "^[A-Z]{3}$") String homeAirport,
      @Nullable Map<String, Object> preferences,
      @Nullable List<@Valid LoyaltyInput> loyalty,
      @Nullable @Valid EmergencyInput emergencyContact,
      @Nullable Long expectedVersion) {
    ProfileService.ProfileUpdate toUpdate() {
      return new ProfileService.ProfileUpdate(
          givenName,
          familyName,
          middleName,
          email,
          phone,
          dateOfBirth,
          gender,
          nationality,
          homeAirport,
          preferences,
          loyalty == null
              ? null
              : loyalty.stream()
                  .map(l -> new TravelerProfile.Loyalty(l.program(), l.memberNumber(), null))
                  .toList(),
          emergencyContact == null
              ? null
              : new TravelerProfile.EmergencyContact(
                  emergencyContact.name(), emergencyContact.phone(), emergencyContact.relation()),
          expectedVersion);
    }
  }

  /** The caller's own profile. */
  @GetMapping("/me")
  public ProfileView me(
      @AuthenticationPrincipal RequestPrincipal me,
      @RequestParam(defaultValue = "false") boolean reveal) {
    return get(me, me.employeeIdOrThrow(), reveal, "SELF_VIEW");
  }

  @PutMapping(path = "/me", consumes = "application/json")
  public ProfileView updateMe(
      @AuthenticationPrincipal RequestPrincipal me, @Valid @RequestBody ProfileRequest request) {
    return update(me, me.employeeIdOrThrow(), request);
  }

  /**
   * @param reveal decrypt phone, date of birth, loyalty numbers and the emergency contact for a
   *     caller allowed to see them; the read is logged with {@code purpose}
   */
  @GetMapping("/{travelerId}")
  public ProfileView get(
      @AuthenticationPrincipal RequestPrincipal me,
      @PathVariable String travelerId,
      @RequestParam(defaultValue = "false") boolean reveal,
      @RequestParam(defaultValue = "PROFILE_VIEW") @Pattern(regexp = "^[A-Z_:0-9-]{1,80}$")
          String purpose) {
    ProfileService.View v = profiles.get(me, travelerId, reveal, purpose);
    return ProfileView.from(
        v.profile(),
        v.relation().name(),
        v.profile().phone() != null || v.profile().dateOfBirth() != null);
  }

  @PutMapping(path = "/{travelerId}", consumes = "application/json")
  public ProfileView update(
      @AuthenticationPrincipal RequestPrincipal me,
      @PathVariable String travelerId,
      @Valid @RequestBody ProfileRequest request) {
    ProfileService.View v = profiles.update(me, travelerId, request.toUpdate());
    return ProfileView.from(v.profile(), v.relation().name(), true);
  }

  @GetMapping("/{travelerId}/changes")
  public List<Map<String, Object>> changes(
      @AuthenticationPrincipal RequestPrincipal me, @PathVariable String travelerId) {
    return profiles.changes(me, travelerId);
  }

  /** Who looked at this traveler's sensitive data, when and why. Self and travel admins only. */
  @GetMapping("/{travelerId}/access-log")
  public List<Map<String, Object>> accessLog(
      @AuthenticationPrincipal RequestPrincipal me, @PathVariable String travelerId) {
    return profiles.sensitiveAccess(me, travelerId);
  }

  // ------------------------------------------------------------------ guests

  @GetMapping("/guests")
  public List<ProfileView> guests(@AuthenticationPrincipal RequestPrincipal me) {
    return profiles.guestsOf(me).stream().map(p -> ProfileView.from(p, "SPONSOR", false)).toList();
  }

  @PostMapping(path = "/guests", consumes = "application/json")
  @ResponseStatus(HttpStatus.CREATED)
  public ProfileView createGuest(
      @AuthenticationPrincipal RequestPrincipal me, @Valid @RequestBody ProfileRequest request) {
    return ProfileView.from(profiles.createGuest(me, request.toUpdate()), "SPONSOR", true);
  }

  // ------------------------------------------------------------------ documents

  public record DocumentView(
      String documentId,
      String travelerId,
      String type,
      @Nullable String number,
      String numberLast4,
      String issuingCountry,
      @Nullable String nationality,
      @Nullable LocalDate issuedOn,
      LocalDate expiresOn,
      String holderGivenName,
      String holderFamilyName,
      boolean revoked,
      LocalDate retentionUntil,
      Instant updatedAt) {
    static DocumentView from(TravelDocument d) {
      return new DocumentView(
          d.documentId(),
          d.travelerId(),
          d.type().name(),
          d.number(),
          d.numberLast4(),
          d.issuingCountry(),
          d.nationality(),
          d.issuedOn(),
          d.expiresOn(),
          d.holderGivenName(),
          d.holderFamilyName(),
          d.revoked(),
          d.retentionUntil(),
          d.updatedAt());
    }
  }

  public record DocumentRequest(
      @NotNull TravelDocument.Type type,
      @NotBlank @Size(max = 40) String number,
      @NotBlank @Pattern(regexp = "^[A-Z]{2}$") String issuingCountry,
      @Nullable @Pattern(regexp = "^[A-Z]{2}$") String nationality,
      @Nullable LocalDate issuedOn,
      @NotNull LocalDate expiresOn,
      @Nullable @Size(max = 80) String holderGivenName,
      @Nullable @Size(max = 80) String holderFamilyName) {}

  @GetMapping("/{travelerId}/documents")
  public List<DocumentView> documents(
      @AuthenticationPrincipal RequestPrincipal me, @PathVariable String travelerId) {
    return profiles.documents(me, travelerId).stream().map(DocumentView::from).toList();
  }

  @PostMapping(path = "/{travelerId}/documents", consumes = "application/json")
  @ResponseStatus(HttpStatus.CREATED)
  public DocumentView addDocument(
      @AuthenticationPrincipal RequestPrincipal me,
      @PathVariable String travelerId,
      @Valid @RequestBody DocumentRequest r) {
    return DocumentView.from(
        profiles.addDocument(
            me,
            travelerId,
            new ProfileService.DocumentInput(
                r.type(),
                r.number(),
                r.issuingCountry(),
                r.nationality(),
                r.issuedOn(),
                r.expiresOn(),
                r.holderGivenName(),
                r.holderFamilyName())));
  }

  /**
   * The full document number: self, sponsor, travel admins, arrangers whose grant allows it.
   * Logged.
   */
  @GetMapping("/documents/{documentId}/number")
  public DocumentView documentNumber(
      @AuthenticationPrincipal RequestPrincipal me,
      @PathVariable String documentId,
      @RequestParam(defaultValue = "DOCUMENT_VIEW") @Pattern(regexp = "^[A-Z_:0-9-]{1,80}$")
          String purpose) {
    return DocumentView.from(profiles.revealDocument(me, documentId, purpose));
  }

  @DeleteMapping("/documents/{documentId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void revokeDocument(
      @AuthenticationPrincipal RequestPrincipal me, @PathVariable String documentId) {
    profiles.revokeDocument(me, documentId);
  }
}
