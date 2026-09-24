package io.travelos.travelcore.trip;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * What a trip is charged to and who is responsible for it, as Enterprise Context stated it when the
 * trip was requested. The manager here is the HRIS manager of the traveler at that moment; the
 * arranger is whoever asked, on the basis Enterprise Context authorized.
 */
public record TripAllocation(
    String tripId,
    @Nullable String departmentId,
    @Nullable String costCenterId,
    @Nullable String legalEntityId,
    @Nullable String officeId,
    @Nullable String projectId,
    boolean projectRestricted,
    @Nullable String managerEmployeeId,
    @Nullable String arrangerEmployeeId,
    String arrangerBasis,
    String travelerKind,
    long profileVersion,
    Instant capturedAt) {}
