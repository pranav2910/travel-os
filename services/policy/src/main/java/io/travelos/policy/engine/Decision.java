package io.travelos.policy.engine;

import io.travelos.common.money.Money;
import io.travelos.policy.document.PolicyDocument.Consequence;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The engine's verdict on one candidate or action. Never a bare boolean: which rules ran, which
 * ones objected, who must approve, and what it costs or earns the traveler.
 */
public record Decision(
    Outcome outcome,
    List<String> rulesEvaluated,
    List<Violation> violations,
    boolean requiresApproval,
    List<String> approverRoles,
    Economics economics) {

  public enum Outcome {
    ALLOW,
    ALLOW_WITH_APPROVAL,
    DENY,
    ALLOW_WITH_TRAVELER_PAYMENT
  }

  /**
   * @param ruleId which rule objected
   * @param code stable machine code, e.g. CABIN_NOT_PERMITTED
   * @param message safe to show the traveler
   * @param approverRole who must approve, when the consequence is REQUIRE_APPROVAL
   */
  public record Violation(
      String ruleId,
      String code,
      String message,
      Consequence consequence,
      @Nullable String approverRole) {}

  public record Economics(
      Money referenceFare, Money inPolicyCeiling, Money travelerIncentive, Money travelerPays) {

    public static Economics none(String currency) {
      Money zero = Money.zero(currency);
      return new Economics(zero, zero, zero, zero);
    }

    Economics withoutMoneyConsequences() {
      return new Economics(
          referenceFare,
          inPolicyCeiling,
          Money.zero(travelerIncentive.currency()),
          Money.zero(travelerPays.currency()));
    }
  }

  public record CandidateEvaluation(String bundleId, Decision decision) {}

  /** Aggregation: DENY beats REQUIRE_APPROVAL beats TRAVELER_PAYS beats ALLOW. */
  static Decision of(List<String> rules, List<Violation> violations, Economics economics) {
    boolean deny = violations.stream().anyMatch(v -> v.consequence() == Consequence.DENY);
    boolean approval =
        violations.stream().anyMatch(v -> v.consequence() == Consequence.REQUIRE_APPROVAL);
    boolean pays = violations.stream().anyMatch(v -> v.consequence() == Consequence.TRAVELER_PAYS);
    Outcome outcome =
        deny
            ? Outcome.DENY
            : approval
                ? Outcome.ALLOW_WITH_APPROVAL
                : pays ? Outcome.ALLOW_WITH_TRAVELER_PAYMENT : Outcome.ALLOW;
    List<String> approvers = new ArrayList<>();
    if (approval) {
      for (Violation v : violations) {
        if (v.consequence() == Consequence.REQUIRE_APPROVAL
            && v.approverRole() != null
            && !approvers.contains(v.approverRole())) {
          approvers.add(v.approverRole());
        }
      }
    }
    // A denied candidate neither earns nor costs anything: it cannot be booked.
    Economics money = deny ? economics.withoutMoneyConsequences() : economics;
    return new Decision(
        outcome,
        List.copyOf(rules),
        List.copyOf(violations),
        outcome == Outcome.ALLOW_WITH_APPROVAL,
        List.copyOf(approvers),
        money);
  }

  public static Decision deny(String ruleId, String code, String message, String currency) {
    return of(
        List.of(ruleId),
        List.of(new Violation(ruleId, code, message, Consequence.DENY, null)),
        Economics.none(currency));
  }
}
