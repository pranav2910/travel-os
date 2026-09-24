package io.travelos.travelcore.api;

import io.travelos.spring.web.auth.RequestPrincipal;
import io.travelos.travelcore.approval.ApprovalDelegate;
import io.travelos.travelcore.trip.TripService;
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

/**
 * Phase 7: delegated approval authority. A manager (or Finance, or a travel admin) hands their
 * authority to a colleague for a period; the colleague decides in their name and the approval
 * record says so.
 */
@RestController
@RequestMapping(path = "/api/v1/approvals/delegates", produces = "application/json")
public class DelegationController {
  private final TripService trips;

  public DelegationController(TripService trips) {
    this.trips = trips;
  }

  public record DelegateView(
      String delegateId,
      String delegatorEmployeeId,
      String delegateEmployeeId,
      Instant validFrom,
      Instant validUntil,
      boolean active,
      String createdBy,
      Instant createdAt,
      @Nullable Instant revokedAt) {
    static DelegateView from(ApprovalDelegate d) {
      return new DelegateView(
          d.delegateId(),
          d.delegatorEmployeeId(),
          d.delegateEmployeeId(),
          d.validFrom(),
          d.validUntil(),
          d.activeAt(Instant.now()),
          d.createdBy(),
          d.createdAt(),
          d.revokedAt());
    }
  }

  public record DelegateRequest(
      @Nullable @Size(max = 64) String delegatorEmployeeId,
      @NotBlank @Size(max = 64) String delegateEmployeeId,
      @Nullable Instant validFrom,
      @NotNull Instant validUntil) {}

  @GetMapping
  public List<DelegateView> list(
      @AuthenticationPrincipal RequestPrincipal me,
      @RequestParam(defaultValue = "false") boolean all) {
    return trips.delegations(me, all).stream().map(DelegateView::from).toList();
  }

  @PostMapping(consumes = "application/json")
  public ResponseEntity<DelegateView> delegate(
      @AuthenticationPrincipal RequestPrincipal me, @Valid @RequestBody DelegateRequest request) {
    ApprovalDelegate d =
        trips.delegateApprovals(
            me,
            request.delegatorEmployeeId(),
            request.delegateEmployeeId(),
            request.validFrom(),
            request.validUntil());
    return ResponseEntity.status(HttpStatus.CREATED).body(DelegateView.from(d));
  }

  @DeleteMapping("/{delegateId}")
  public ResponseEntity<Void> revoke(
      @AuthenticationPrincipal RequestPrincipal me, @PathVariable String delegateId) {
    trips.revokeDelegation(me, delegateId);
    return ResponseEntity.noContent().build();
  }
}
