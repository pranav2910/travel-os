package io.travelos.learning.model;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

public record ActivationRecord(
    long id,
    String action,
    @Nullable String profileId,
    @Nullable String previousProfileId,
    LearningMode mode,
    @Nullable LearningMode previousMode,
    long configVersion,
    String actor,
    Instant occurredAt) {}
