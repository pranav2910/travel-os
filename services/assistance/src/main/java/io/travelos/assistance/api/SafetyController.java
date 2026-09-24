package io.travelos.assistance.api;

import io.travelos.assistance.safety.SafetyRecords.Advisory;
import io.travelos.assistance.safety.SafetyRecords.Affected;
import io.travelos.assistance.safety.SafetyRecords.Checkin;
import io.travelos.assistance.safety.SafetyRecords.CheckinStatus;
import io.travelos.assistance.safety.SafetyRecords.Severity;
import io.travelos.assistance.safety.SafetyService;
import io.travelos.spring.web.auth.RequestPrincipal;
import io.travelos.spring.web.idempotency.IdempotencyKeyHeader;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Phase 8: safety advisories for travel admins; the traveler's own view and check-in. */
@RestController
@RequestMapping(path = "/api/v1/safety", produces = "application/json")
public class SafetyController {
  private final SafetyService safety;

  public SafetyController(SafetyService safety) {
    this.safety = safety;
  }

  public record AdvisoryView(
      String advisoryId,
      String title,
      String severity,
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
    static AdvisoryView from(Advisory a) {
      return new AdvisoryView(
          a.advisoryId(),
          a.title(),
          a.severity().name(),
          a.countries(),
          a.cities(),
          a.startsAt(),
          a.endsAt(),
          a.text(),
          a.source(),
          a.active(),
          a.issuedBy(),
          a.issuedAt(),
          a.checkinDueAt());
    }
  }

  public record AffectedView(
      String travelerId,
      String tripId,
      Instant notifiedAt,
      @Nullable String caseId,
      @Nullable String checkin,
      @Nullable Instant checkedInAt) {}

  public record DetailView(
      AdvisoryView advisory, List<AffectedView> affected, int checkedIn, int needHelp) {}

  public record IssueRequest(
      @NotBlank @Size(max = 300) String title,
      @NotNull Severity severity,
      @Nullable List<String> countries,
      @Nullable List<String> cities,
      @NotNull Instant startsAt,
      @NotNull Instant endsAt,
      @NotBlank @Size(max = 4000) String text,
      @Nullable @Size(max = 200) String source) {}

  public record CheckinRequest(
      @NotNull CheckinStatus status, @Nullable @Size(max = 2000) String note) {}

  public record CheckinView(
      String checkinId,
      String advisoryId,
      String travelerId,
      String status,
      @Nullable String note,
      Instant recordedAt) {
    static CheckinView from(Checkin c) {
      return new CheckinView(
          c.checkinId(),
          c.advisoryId(),
          c.travelerId(),
          c.status().name(),
          c.note(),
          c.recordedAt());
    }
  }

  @GetMapping("/advisories")
  public List<AdvisoryView> list(
      @AuthenticationPrincipal RequestPrincipal me,
      @RequestParam(defaultValue = "true") boolean active) {
    return safety.list(me, active).stream().map(AdvisoryView::from).toList();
  }

  @PostMapping(path = "/advisories", consumes = "application/json")
  public ResponseEntity<DetailView> issue(
      @AuthenticationPrincipal RequestPrincipal me,
      @IdempotencyKeyHeader String idempotencyKey,
      @Valid @RequestBody IssueRequest request) {
    Advisory a =
        safety.issue(
            me,
            new SafetyService.Issue(
                request.title(),
                request.severity(),
                request.countries() == null ? List.of() : request.countries(),
                request.cities() == null ? List.of() : request.cities(),
                request.startsAt(),
                request.endsAt(),
                request.text(),
                request.source()));
    return ResponseEntity.status(HttpStatus.CREATED).body(detail(safety.get(me, a.advisoryId())));
  }

  @GetMapping("/advisories/{advisoryId}")
  public DetailView get(
      @AuthenticationPrincipal RequestPrincipal me, @PathVariable String advisoryId) {
    return detail(safety.get(me, advisoryId));
  }

  @DeleteMapping("/advisories/{advisoryId}")
  public ResponseEntity<Void> close(
      @AuthenticationPrincipal RequestPrincipal me, @PathVariable String advisoryId) {
    safety.close(me, advisoryId);
    return ResponseEntity.noContent().build();
  }

  @PostMapping(path = "/advisories/{advisoryId}/checkin", consumes = "application/json")
  public CheckinView checkin(
      @AuthenticationPrincipal RequestPrincipal me,
      @PathVariable String advisoryId,
      @IdempotencyKeyHeader String idempotencyKey,
      @Valid @RequestBody CheckinRequest request) {
    return CheckinView.from(safety.checkin(me, advisoryId, request.status(), request.note()));
  }

  private static DetailView detail(SafetyService.Detail d) {
    java.util.Map<String, Checkin> byTraveler = new java.util.HashMap<>();
    d.checkins().forEach(c -> byTraveler.put(c.travelerId(), c));
    List<AffectedView> affected = new java.util.ArrayList<>();
    for (Affected f : d.affected()) {
      Checkin c = byTraveler.get(f.travelerId());
      affected.add(
          new AffectedView(
              f.travelerId(),
              f.tripId(),
              f.notifiedAt(),
              f.caseId(),
              c == null ? null : c.status().name(),
              c == null ? null : c.recordedAt()));
    }
    int safe = (int) d.checkins().stream().filter(c -> c.status() == CheckinStatus.SAFE).count();
    int help =
        (int) d.checkins().stream().filter(c -> c.status() == CheckinStatus.NEEDS_HELP).count();
    return new DetailView(AdvisoryView.from(d.advisory()), affected, safe, help);
  }
}
