package io.travelos.workflows.trip;

import io.travelos.contracts.policy.v1.EvaluationScope;
import io.travelos.contracts.policy.v1.Governance;
import io.travelos.contracts.policy.v1.PolicyDecision;
import io.travelos.contracts.policy.v1.SupplierAgreement;
import io.travelos.contracts.supplier.v1.NegotiatedRate;
import io.travelos.contracts.trip.v1.Trip;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Phase 7: how the workflow carries the trip's governance to policy, suppliers and Travel Core. */
final class Governed {
  private Governed() {}

  /** The trip's allocation snapshot, as the scope policy resolves against. */
  static EvaluationScope scopeOf(Trip trip) {
    if (!trip.hasAllocation()) {
      return EvaluationScope.getDefaultInstance();
    }
    var a = trip.getAllocation();
    return EvaluationScope.newBuilder()
        .setDepartmentId(a.getDepartmentId())
        .setCostCenterId(a.getCostCenterId())
        .setProjectId(a.getProjectId())
        .setLegalEntityId(a.getLegalEntityId())
        .setOfficeId(a.getOfficeId())
        .build();
  }

  /** The decision's approval chain, or the single role when the policy names no chain. */
  static List<String> chain(PolicyDecision decision, String firstRole) {
    List<String> roles = new ArrayList<>();
    decision.getApprovalChainList().forEach(a -> roles.add(a.getRole()));
    if (roles.isEmpty()) {
      roles.add(firstRole);
    }
    return roles;
  }

  static List<NegotiatedRate> airRates(@Nullable Governance governance) {
    return rates(governance, "AIR");
  }

  static List<NegotiatedRate> hotelRates(@Nullable Governance governance) {
    return rates(governance, "HOTEL");
  }

  private static List<NegotiatedRate> rates(@Nullable Governance governance, String kind) {
    List<NegotiatedRate> out = new ArrayList<>();
    if (governance == null) {
      return out;
    }
    for (SupplierAgreement a : governance.getAgreementsList()) {
      if (a.getNegotiated() && kind.equals(a.getKind()) && !a.getRateCode().isBlank()) {
        out.add(
            NegotiatedRate.newBuilder()
                .setCarrier(a.getCarrier())
                .setRateCode(a.getRateCode())
                .build());
      }
    }
    return out;
  }
}
