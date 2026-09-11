package io.travelos.policy.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.travelos.events.testing.EventSchemas;
import org.junit.jupiter.api.Test;

class PolicyDocumentsTest {

  private static final String SEED = EventSchemas.resource("policies/acme-us-standard.json");

  @Test
  void parsesTheSeedPolicy() {
    PolicyDocument policy = PolicyDocuments.parse(SEED);
    assertThat(policy.policyId()).isEqualTo("US_STANDARD_TRAVEL");
    assertThat(policy.flight().lowestLogicalFare().maxAmountAbove()).isEqualTo(15000);
    assertThat(policy.flight().lowestLogicalFare().onViolation())
        .isEqualTo(PolicyDocument.Consequence.TRAVELER_PAYS);
    assertThat(policy.autonomy().flightRebooking().maxIncrementalCost()).isEqualTo(10000);
    assertThat(policy.incentives().shareOfSavings()).isEqualTo(0.25);
  }

  @Test
  void hashIsStableAndContentBased() {
    PolicyDocument a = PolicyDocuments.parse(SEED);
    PolicyDocument b = PolicyDocuments.parse(SEED.replace("  ", " "));
    assertThat(PolicyDocuments.hash(a)).isEqualTo(PolicyDocuments.hash(b)).hasSize(64);
    PolicyDocument changed =
        PolicyDocuments.parse(SEED.replace("\"maxReward\": 5000", "\"maxReward\": 5001"));
    assertThat(PolicyDocuments.hash(changed)).isNotEqualTo(PolicyDocuments.hash(a));
  }

  @Test
  void unknownFieldsAreRejectedSoTyposCannotSilentlyDisableRules() {
    assertThatThrownBy(() -> PolicyDocuments.parse(SEED.replace("\"maxStops\"", "\"maxStop\"")))
        .isInstanceOf(PolicyDocuments.InvalidPolicyException.class)
        .hasMessageContaining("maxStop");
  }

  @Test
  void missingSectionsAndBadValuesAreReportedTogether() {
    String broken =
        """
        {"policyId":"lowercase","name":"","currency":"DOLLARS",
         "flight":{"domesticCabins":[],"internationalCabins":["ECONOMY"],
                   "lowestLogicalFare":{"maxAmountAbove":-1,"onViolation":"DENY"}},
         "approval":{"managerRequiredAbove":-5},
         "autonomy":{"flightRebooking":{"enabled":true,"maxIncrementalCost":100},"cancellation":{"enabled":false}},
         "incentives":{"enabled":true,"shareOfSavings":1.5,"maxReward":0}}
        """;
    assertThatThrownBy(() -> PolicyDocuments.parse(broken))
        .isInstanceOfSatisfying(
            PolicyDocuments.InvalidPolicyException.class,
            e ->
                assertThat(e.problems())
                    .anyMatch(p -> p.contains("policyId"))
                    .anyMatch(p -> p.contains("name"))
                    .anyMatch(p -> p.contains("currency"))
                    .anyMatch(p -> p.contains("domesticCabins"))
                    .anyMatch(p -> p.contains("maxAmountAbove"))
                    .anyMatch(p -> p.contains("hotel section"))
                    .anyMatch(p -> p.contains("managerRequiredAbove"))
                    .anyMatch(p -> p.contains("shareOfSavings")));
  }
}
