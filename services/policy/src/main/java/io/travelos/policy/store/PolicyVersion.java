package io.travelos.policy.store;

import io.travelos.common.identity.Principal;
import io.travelos.common.tenant.TenantId;
import io.travelos.policy.document.PolicyDocument;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

public record PolicyVersion(
    TenantId tenant,
    String policyId,
    int version,
    PolicyDocument document,
    String documentHash,
    Principal publishedBy,
    Instant publishedAt,
    @Nullable String note) {

  public record Summary(
      String policyId, String name, int currentVersion, boolean isDefault, Instant publishedAt) {}
}
