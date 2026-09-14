package io.travelos.disruption.service;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import io.grpc.Status;
import io.travelos.common.identity.Principal;
import io.travelos.common.ids.IdPrefix;
import io.travelos.common.ids.Ids;
import io.travelos.common.money.Money;
import io.travelos.common.tenant.TenantId;
import io.travelos.contracts.disruption.v1.DisruptionStatus;
import io.travelos.contracts.disruption.v1.RecoveryDecision;
import io.travelos.contracts.disruption.v1.RecoveryOutcome;
import io.travelos.contracts.disruption.v1.RecoveryState;
import io.travelos.contracts.disruption.v1.RejectedCandidate;
import io.travelos.contracts.policy.v1.ReasonCode;
import io.travelos.disruption.events.DisruptionEvents;
import io.travelos.disruption.metrics.RecoveryMetrics;
import io.travelos.disruption.model.Disruption;
import io.travelos.disruption.model.RecoveryApproval;
import io.travelos.disruption.store.DisruptionRepository;
import io.travelos.spring.outbox.Outbox;
import io.travelos.spring.web.auth.RequestPrincipal;
import io.travelos.spring.web.error.ApiException;
import io.travelos.workflows.DisruptionRecovery;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The state machine and its evidence. The recovery workflow asks for transitions; this service
 * checks them against {@link io.travelos.disruption.model.DisruptionStatus}, records them with the
 * matching event in one transaction, and keeps the two immutable records. Transitions are
 * idempotent by state: asking for the status the disruption already has returns it unchanged.
 */
@Service
public class DisruptionService {

  private static final Logger log = LoggerFactory.getLogger(DisruptionService.class);
  private static final JsonFormat.Printer PRINTER =
      JsonFormat.printer().omittingInsignificantWhitespace();
  private static final JsonFormat.Parser PARSER = JsonFormat.parser().ignoringUnknownFields();

  private final DisruptionRepository repository;
  private final Outbox outbox;
  private final RecoverySignaler signaler;
  private final RecoveryMetrics metrics;
  private final Clock clock;

  public DisruptionService(
      DisruptionRepository repository,
      Outbox outbox,
      RecoverySignaler signaler,
      RecoveryMetrics metrics,
      Clock clock) {
    this.repository = repository;
    this.outbox = outbox;
    this.signaler = signaler;
    this.metrics = metrics;
    this.clock = clock;
  }

  public Disruption get(TenantId tenant, String disruptionId) {
    return repository
        .find(tenant, disruptionId)
        .orElseThrow(
            () ->
                Status.NOT_FOUND
                    .withDescription("disruption " + disruptionId + " not found")
                    .asRuntimeException());
  }

  public List<Disruption> byTrip(TenantId tenant, String tripId) {
    return repository.byTrip(tenant, tripId);
  }

  public Optional<String> decisionJson(TenantId tenant, String disruptionId) {
    return repository.decision(tenant, disruptionId);
  }

  public Optional<String> outcomeJson(TenantId tenant, String disruptionId) {
    return repository.outcome(tenant, disruptionId);
  }

  public Optional<RecoveryApproval> latestApproval(TenantId tenant, String disruptionId) {
    return repository.latestApproval(tenant, disruptionId);
  }

  public List<String[]> history(TenantId tenant, String disruptionId) {
    return repository.history(tenant, disruptionId);
  }

  // ------------------------------------------------------------------ workflow-driven transitions

  /**
   * @param recovery when present, replaces the stored RecoveryState (the workflow always sends the
   *     whole state it knows; ids only ever accumulate)
   */
  @Transactional
  public Disruption transition(
      TenantId tenant,
      String disruptionId,
      DisruptionStatus toProto,
      @Nullable String reason,
      @Nullable String approverRole,
      @Nullable RecoveryState recovery,
      @Nullable String failureStage,
      @Nullable String failureCode) {
    io.travelos.disruption.model.DisruptionStatus to =
        io.travelos.disruption.model.DisruptionStatus.valueOf(toProto.name());
    Disruption d = get(tenant, disruptionId);
    if (d.status() == to) {
      return d;
    }
    if (!d.status().canTransitionTo(to)) {
      throw Status.FAILED_PRECONDITION
          .withDescription("ILLEGAL_TRANSITION: " + d.status() + " -> " + to)
          .asRuntimeException();
    }
    if (to.isTerminalFailure()) {
      throw Status.FAILED_PRECONDITION
          .withDescription("USE_RECORD_OUTCOME: terminal states are recorded with their outcome")
          .asRuntimeException();
    }
    Instant now = clock.instant();
    String recoveryJson = recovery == null ? null : print(recovery);
    if (!repository.transition(d, to, reason, null, recoveryJson, failureStage, failureCode, now)) {
      throw Status.ABORTED
          .withDescription("disruption changed concurrently; retry")
          .asRuntimeException();
    }
    Disruption next = get(tenant, disruptionId);
    switch (to) {
      case SEARCHING_ALTERNATIVES -> {
        metrics.attempt();
        outbox.append(
            DisruptionEvents.recoveryStarted(
                next,
                DisruptionRecovery.workflowId(disruptionId),
                DisruptionRecovery.AGENT,
                clock));
      }
      case AUTO_ALLOWED -> metrics.autonomous();
      case HUMAN_REQUIRED -> {
        metrics.escalated();
        RecoveryState state = recovery == null ? RecoveryState.getDefaultInstance() : recovery;
        RecoveryApproval approval = requestApproval(next, state, approverRole);
        // the approval id is part of the recovery state from now on
        RecoveryState withApproval =
            state.toBuilder()
                .setApprovalId(approval.approvalId())
                .setApprovalStatus("PENDING")
                .build();
        repository.transition(
            next, to, null, null, print(withApproval), failureStage, failureCode, now);
        next = get(tenant, disruptionId);
        outbox.append(DisruptionEvents.approvalRequired(next, approval, reasonCodes(next), clock));
      }
      default -> {}
    }
    return next;
  }

  private RecoveryApproval requestApproval(
      Disruption d, RecoveryState state, @Nullable String role) {
    RecoveryApproval approval =
        new RecoveryApproval(
            Ids.newId(IdPrefix.APPROVAL),
            d.disruptionId(),
            d.tenant(),
            d.tripId() == null ? "" : d.tripId(),
            d.travelerId() == null ? "" : d.travelerId(),
            role == null || role.isBlank() ? "MANAGER" : role,
            RecoveryApproval.Status.PENDING,
            state.getPolicyDecisionId().isBlank() ? null : state.getPolicyDecisionId(),
            state.hasIncrementalCost()
                ? Money.of(
                    state.getIncrementalCost().getCurrency(),
                    state.getIncrementalCost().getAmountMinor())
                : null,
            clock.instant(),
            null,
            null,
            null,
            null);
    repository.insertApproval(approval);
    return approval;
  }

  /** Written once. A retry (same disruption) returns the disruption with the stored record. */
  @Transactional
  public Disruption recordDecision(
      TenantId tenant, String disruptionId, RecoveryDecision decision) {
    Disruption d = get(tenant, disruptionId);
    if (repository.decision(tenant, disruptionId).isPresent()) {
      return d;
    }
    Instant now = clock.instant();
    String decisionId = Ids.newId(IdPrefix.RECOVERY_DECISION);
    RecoveryDecision stored =
        decision.toBuilder()
            .setDecisionId(decisionId)
            .setDisruptionId(disruptionId)
            .setDecidedAt(ts(now))
            .build();
    repository.insertDecision(decisionId, d, print(stored), now);
    RecoveryState state =
        currentRecovery(d).toBuilder()
            .setReplacementBundleId(stored.getSelected().getBundleId())
            .setIncrementalCost(stored.getIncrementalCost())
            .setPolicyDecisionId(stored.getPolicyDecision().getDecisionId())
            .setOptimizationRunId(stored.getOptimizationRunId())
            .setAutonomyOutcome(stored.getAutonomyOutcome())
            .build();
    if (d.status() != io.travelos.disruption.model.DisruptionStatus.DECISION_READY) {
      if (!d.status()
          .canTransitionTo(io.travelos.disruption.model.DisruptionStatus.DECISION_READY)) {
        throw Status.FAILED_PRECONDITION
            .withDescription("ILLEGAL_TRANSITION: " + d.status() + " -> DECISION_READY")
            .asRuntimeException();
      }
      repository.transition(
          d,
          io.travelos.disruption.model.DisruptionStatus.DECISION_READY,
          "decision " + decisionId,
          null,
          print(state),
          null,
          null,
          now);
    }
    Disruption ready = get(tenant, disruptionId);
    List<Map<String, Object>> rejected = new ArrayList<>();
    for (RejectedCandidate r : stored.getRejectedList()) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("bundleId", r.getBundleId());
      m.put("stage", r.getStage());
      m.put("reasonCodes", r.getReasonCodesList());
      if (r.hasTotal()) {
        m.put("total", DisruptionEvents.money(money(r.getTotal())));
      }
      rejected.add(m);
    }
    List<String> codes = new ArrayList<>();
    for (ReasonCode rc : stored.getPolicyDecision().getReasonsList()) {
      codes.add(rc.getCode());
    }
    outbox.append(
        DisruptionEvents.decisionReady(
            ready,
            new DisruptionEvents.DecisionSummary(
                stored.getCandidatesSearched(),
                stored.getCandidatesPermitted(),
                stored.getCandidatesFeasible(),
                rejected,
                stored.getSelected().getBundleId(),
                stored.hasSelectedRanking() ? stored.getSelectedRanking().getScore() : null,
                stored.hasOriginalTotal() ? money(stored.getOriginalTotal()) : null,
                stored.hasReplacementTotal() ? money(stored.getReplacementTotal()) : null,
                money(stored.getIncrementalCost()),
                stored.getPolicyDecision().getDecisionId(),
                stored.getPolicyDecision().getPolicyId(),
                stored.getPolicyDecision().getPolicyVersion(),
                stored.getOptimizationRunId(),
                stored.getAutonomyOutcome(),
                codes),
            clock));
    return ready;
  }

  /** Written once; moves the disruption to its terminal state and tells the world. */
  @Transactional
  public Disruption recordOutcome(TenantId tenant, String disruptionId, RecoveryOutcome outcome) {
    Disruption d = get(tenant, disruptionId);
    if (repository.outcome(tenant, disruptionId).isPresent()) {
      return d;
    }
    io.travelos.disruption.model.DisruptionStatus to =
        io.travelos.disruption.model.DisruptionStatus.valueOf(outcome.getStatus().name());
    if (!to.isTerminal()) {
      throw Status.INVALID_ARGUMENT
          .withDescription("outcome.status must be terminal, got " + to)
          .asRuntimeException();
    }
    if (d.status() != to && !d.status().canTransitionTo(to)) {
      throw Status.FAILED_PRECONDITION
          .withDescription("ILLEGAL_TRANSITION: " + d.status() + " -> " + to)
          .asRuntimeException();
    }
    Instant now = clock.instant();
    String outcomeId = Ids.newId(IdPrefix.RECOVERY_OUTCOME);
    RecoveryOutcome stored =
        outcome.toBuilder()
            .setOutcomeId(outcomeId)
            .setDisruptionId(disruptionId)
            .setCompletedAt(ts(now))
            .build();
    repository.insertOutcome(outcomeId, d, to, print(stored), now);
    RecoveryState state = currentRecovery(d);
    RecoveryState.Builder sb = state.toBuilder();
    if (!stored.getApprovalId().isBlank()) {
      sb.setApprovalId(stored.getApprovalId());
    }
    if (to.isTerminalFailure()) {
      sb.setFailureStage(stored.getFailureStage()).setFailureCode(stored.getFailureCode());
    }
    if (d.status() != to) {
      repository.transition(
          d,
          to,
          to.isTerminalFailure() ? stored.getFailureCode() : "resolved",
          null,
          print(sb.build()),
          to.isTerminalFailure() ? stored.getFailureStage() : null,
          to.isTerminalFailure() ? stored.getFailureCode() : null,
          now);
    }
    Disruption done = get(tenant, disruptionId);
    if (to == io.travelos.disruption.model.DisruptionStatus.RESOLVED) {
      Money incremental =
          state.hasIncrementalCost() ? money(state.getIncrementalCost()) : Money.zero("USD");
      // detectedAt is the supplier's clock; never let a skewed one produce a negative duration
      Duration took = Duration.between(done.detectedAt(), now);
      if (took.isNegative()) {
        took = Duration.ZERO;
      }
      metrics.resolved(took, incremental.amountMinor());
      outbox.append(
          DisruptionEvents.resolved(
              done,
              new DisruptionEvents.Resolution(
                  state.getReplacementBundleId(),
                  incremental,
                  state.getAutonomyOutcome(),
                  blankToNull(stored.getApprovalId()),
                  blankToNull(stored.getApprovedBy()),
                  DisruptionRecovery.AGENT,
                  stored.hasSupplierResult()
                      ? stored.getSupplierResult().getExternalOrderId()
                      : null,
                  stored.hasSupplierResult() ? stored.getSupplierResult().getRecordLocator() : null,
                  took.toMillis()),
              clock));
    } else {
      metrics.failed();
      outbox.append(
          DisruptionEvents.recoveryFailed(
              done,
              to.name(),
              stored.getFailureStage().isBlank() ? "RECOVERY" : stored.getFailureStage(),
              stored.getFailureCode().isBlank() ? to.name() : stored.getFailureCode(),
              stored.getMessage(),
              clock));
    }
    return done;
  }

  // ------------------------------------------------------------------ people

  /** A manager decides. Mirrors Travel Core's trip approvals: role-checked, no self-approval. */
  @Transactional
  public RecoveryApproval decideApproval(
      RequestPrincipal me,
      String disruptionId,
      RecoveryApproval.Status decision,
      @Nullable String comment,
      String idempotencyKey) {
    if (decision == RecoveryApproval.Status.PENDING) {
      throw new IllegalArgumentException("decision must be APPROVED or REJECTED");
    }
    Disruption d =
        repository
            .find(me.tenant(), disruptionId)
            .filter(x -> DisruptionAccess.canRead(me, x))
            .orElseThrow(() -> new ApiException.NotFound("disruption", disruptionId));
    if (!me.hasAnyRole("MANAGER", "TRAVEL_ADMIN")) {
      throw new ApiException.Forbidden(
          "NOT_AN_APPROVER", "only MANAGER or TRAVEL_ADMIN may decide recovery approvals");
    }
    if (d.travelerId() != null && d.travelerId().equals(me.employeeId())) {
      throw new ApiException.Forbidden(
          "SELF_APPROVAL", "a traveler cannot approve the recovery of their own trip");
    }
    Optional<RecoveryApproval> pending = repository.pendingApproval(me.tenant(), disruptionId);
    if (pending.isEmpty()) {
      RecoveryApproval latest =
          repository
              .latestApproval(me.tenant(), disruptionId)
              .orElseThrow(
                  () ->
                      new ApiException.Conflict(
                          "NO_PENDING_APPROVAL", "this recovery is not awaiting approval"));
      if (idempotencyKey.equals(latest.decisionIdempotencyKey())) {
        return latest;
      }
      throw new ApiException.Conflict(
          "APPROVAL_ALREADY_DECIDED",
          "approval " + latest.approvalId() + " was already " + latest.status());
    }
    Instant now = clock.instant();
    if (!repository.decide(
        pending.get(), decision, me.principal().id(), comment, idempotencyKey, now)) {
      throw new ApiException.Conflict("APPROVAL_ALREADY_DECIDED", "decided concurrently; re-read");
    }
    RecoveryApproval decided =
        repository.findApproval(me.tenant(), pending.get().approvalId()).orElseThrow();
    RecoveryState state =
        currentRecovery(d).toBuilder().setApprovalStatus(decided.status().name()).build();
    repository.transition(
        d, d.status(), null, null, print(state), d.failureStage(), d.failureCode(), now);
    outbox.append(
        DisruptionEvents.approvalDecided(d, decided, me.principal().id(), comment, clock));
    signaler.approvalDecided(
        disruptionId,
        new DisruptionRecovery.ApprovalDecision(
            decided.approvalId(), decided.status().name(), me.principal().id(), comment));
    return decided;
  }

  // ------------------------------------------------------------------ helpers

  public RecoveryState currentRecovery(Disruption d) {
    RecoveryState.Builder b = RecoveryState.newBuilder();
    try {
      PARSER.merge(d.recoveryJson(), b);
    } catch (InvalidProtocolBufferException e) {
      log.warn("unreadable recovery state on {}: {}", d.disruptionId(), e.getMessage());
    }
    return b.build();
  }

  private List<String> reasonCodes(Disruption d) {
    return repository
        .decision(d.tenant(), d.disruptionId())
        .map(
            json -> {
              RecoveryDecision.Builder b = RecoveryDecision.newBuilder();
              try {
                PARSER.merge(json, b);
              } catch (InvalidProtocolBufferException e) {
                return List.<String>of();
              }
              List<String> codes = new ArrayList<>();
              b.getPolicyDecision().getReasonsList().forEach(r -> codes.add(r.getCode()));
              return codes;
            })
        .orElse(List.of());
  }

  static String print(com.google.protobuf.Message m) {
    try {
      return PRINTER.print(m);
    } catch (InvalidProtocolBufferException e) {
      throw new IllegalStateException(e);
    }
  }

  static Money money(io.travelos.contracts.common.v1.Money m) {
    return Money.of(m.getCurrency().isBlank() ? "USD" : m.getCurrency(), m.getAmountMinor());
  }

  static com.google.protobuf.Timestamp ts(Instant i) {
    return com.google.protobuf.Timestamp.newBuilder()
        .setSeconds(i.getEpochSecond())
        .setNanos(i.getNano())
        .build();
  }

  static @Nullable String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s;
  }

  /** Who may see a disruption: the traveler it hit, and the tenant-wide roles. */
  public static final class DisruptionAccess {
    private static final String[] TENANT_WIDE = {"MANAGER", "TRAVEL_ADMIN", "FINANCE"};

    private DisruptionAccess() {}

    public static boolean canRead(RequestPrincipal me, Disruption d) {
      return (d.travelerId() != null && d.travelerId().equals(me.employeeId()))
          || me.hasAnyRole(TENANT_WIDE);
    }

    static Principal unused() {
      return null;
    }
  }
}
