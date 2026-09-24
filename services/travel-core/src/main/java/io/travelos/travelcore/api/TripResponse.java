package io.travelos.travelcore.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.travelos.travelcore.api.ApprovalController.ApprovalResponse;
import io.travelos.travelcore.approval.Approval;
import io.travelos.travelcore.trip.Trip;
import io.travelos.travelcore.trip.TripAllocation;
import io.travelos.travelcore.trip.TripComponent;
import io.travelos.travelcore.trip.TripEvidence;
import io.travelos.travelcore.trip.TripSource;
import io.travelos.travelcore.trip.TripStatus;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TripResponse(
    String tripId,
    String tenantId,
    String travelerId,
    TripStatus status,
    TripSource source,
    @Nullable String request,
    @Nullable IntentRequest intent,
    TripEvidence evidence,
    String createdBy,
    long version,
    Instant createdAt,
    Instant updatedAt,
    TravelerView traveler,
    @Nullable MoneyView total,
    @Nullable ApprovalResponse approval,
    @Nullable String failureStage,
    @Nullable String failureCode,
    @Nullable String explanation,
    @Nullable List<ComponentView> components,
    @Nullable String sourceReference,
    @Nullable AllocationView allocation) {

  /** Cost allocation and responsibility captured when the trip was requested (ADR-0014). */
  public record AllocationView(
      @Nullable String departmentId,
      @Nullable String costCenterId,
      @Nullable String legalEntityId,
      @Nullable String officeId,
      @Nullable String projectId,
      boolean projectRestricted,
      @Nullable String managerEmployeeId,
      @Nullable String arrangerEmployeeId,
      String arrangerBasis,
      String travelerKind) {
    public static AllocationView from(TripAllocation a) {
      return new AllocationView(
          a.departmentId(),
          a.costCenterId(),
          a.legalEntityId(),
          a.officeId(),
          a.projectId(),
          a.projectRestricted(),
          a.managerEmployeeId(),
          a.arrangerEmployeeId(),
          a.arrangerBasis(),
          a.travelerKind());
    }
  }

  /** Slice 3: where one component of the itinerary stands. */
  public record ComponentView(
      String componentId,
      String type,
      String status,
      @Nullable String offerId,
      @Nullable String provider,
      @Nullable String externalRef,
      @Nullable MoneyView total,
      @Nullable String failureCode,
      @Nullable String summary,
      Instant updatedAt) {
    public static ComponentView from(TripComponent c) {
      return new ComponentView(
          c.componentId(),
          c.type(),
          c.status(),
          c.offerId(),
          c.provider(),
          c.externalRef(),
          c.total() == null
              ? null
              : new MoneyView(c.total().currency(), c.total().amountMinor(), c.total().toString()),
          c.failureCode(),
          c.summary(),
          c.updatedAt());
    }
  }

  public record TravelerView(
      String travelerId, String givenName, String familyName, String email) {}

  public record MoneyView(String currency, long amountMinor, String display) {}

  public static TripResponse from(Trip trip) {
    return from(trip, null);
  }

  public static TripResponse from(Trip trip, @Nullable Approval approval) {
    return from(trip, approval, List.of());
  }

  public static TripResponse from(
      Trip trip, @Nullable Approval approval, List<TripComponent> components) {
    return from(trip, approval, components, null);
  }

  public static TripResponse from(
      Trip trip,
      @Nullable Approval approval,
      List<TripComponent> components,
      @Nullable TripAllocation allocation) {
    return new TripResponse(
        trip.tripId(),
        trip.tenantId().value(),
        trip.travelerId(),
        trip.status(),
        trip.source(),
        trip.requestText(),
        trip.intent() == null ? null : IntentRequest.from(trip.intent()),
        trip.evidence(),
        trip.createdBy().id(),
        trip.version(),
        trip.createdAt(),
        trip.updatedAt(),
        new TravelerView(
            trip.traveler().travelerId(),
            trip.traveler().givenName(),
            trip.traveler().familyName(),
            trip.traveler().email()),
        trip.total() == null
            ? null
            : new MoneyView(
                trip.total().currency(), trip.total().amountMinor(), trip.total().toString()),
        approval == null ? null : ApprovalResponse.from(approval),
        trip.failureStage(),
        trip.failureCode(),
        trip.explanation(),
        components.isEmpty() ? null : components.stream().map(ComponentView::from).toList(),
        trip.sourceReference(),
        allocation == null ? null : AllocationView.from(allocation));
  }
}
