package io.travelos.travelcore.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CancelTripRequest(@NotBlank @Size(max = 500) String reason) {}
