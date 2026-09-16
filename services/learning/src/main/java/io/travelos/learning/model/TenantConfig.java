package io.travelos.learning.model;

import io.travelos.common.tenant.TenantId;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

public record TenantConfig(
    TenantId tenant,
    LearningMode mode,
    @Nullable String activeProfileId,
    @Nullable String previousProfileId,
    long version,
    String updatedBy,
    Instant updatedAt) {}
