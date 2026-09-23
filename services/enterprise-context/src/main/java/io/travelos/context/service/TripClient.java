package io.travelos.context.service;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.travelos.common.tenant.TenantId;
import io.travelos.context.model.Employee;
import io.travelos.contracts.common.v1.Principal;
import io.travelos.contracts.common.v1.RequestContext;
import io.travelos.contracts.trip.v1.CreateTripRequest;
import io.travelos.contracts.trip.v1.TravelCoreServiceGrpc;
import io.travelos.contracts.trip.v1.TravelIntent;
import io.travelos.contracts.trip.v1.TravelerIdentity;
import io.travelos.contracts.trip.v1.Trip;
import io.travelos.spring.grpc.GrpcChannels;
import io.travelos.spring.web.auth.RequestPrincipal;
import io.travelos.spring.web.error.ApiException;
import java.util.concurrent.TimeUnit;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Travel Core, the owner of trip requests. Conversion is the only reason this service calls it. */
@Component
public class TripClient {
  private final GrpcChannels channels;

  public TripClient(GrpcChannels channels) {
    this.channels = channels;
  }

  public Trip createTrip(
      TenantId tenant,
      RequestPrincipal me,
      Employee traveler,
      TravelIntent intent,
      String idempotencyKey,
      String candidateId) {
    // The reservation is made in the traveler's name: the verified HRIS identity goes along, so a
    // manager converting a colleague's demand is not refused for an anonymous traveler.
    String[] names = traveler.displayName().trim().split("\\s+", 2);
    CreateTripRequest request =
        CreateTripRequest.newBuilder()
            .setTraveler(
                TravelerIdentity.newBuilder()
                    .setGivenName(names[0])
                    .setFamilyName(names.length > 1 ? names[1] : names[0])
                    .setEmail(traveler.email()))
            .setCtx(
                RequestContext.newBuilder()
                    .setTenantId(tenant.value())
                    .setCorrelationId(candidateId)
                    .setIdempotencyKey(idempotencyKey)
                    .setPrincipal(
                        Principal.newBuilder()
                            .setKind(Principal.Kind.HUMAN)
                            .setId(me.principal().id())))
            .setTravelerId(traveler.employeeId())
            .setIntent(intent)
            .setSource("DEMAND")
            .setSourceReference(candidateId)
            .addAllActorRoles(me.roles())
            .setActorEmployeeId(me.employeeId() == null ? "" : me.employeeId())
            .build();
    try {
      return TravelCoreServiceGrpc.newBlockingStub(channels.channel("travel-core"))
          .withDeadlineAfter(channels.deadline("travel-core").toMillis(), TimeUnit.MILLISECONDS)
          .createTrip(request);
    } catch (StatusRuntimeException e) {
      String description =
          e.getStatus().getDescription() == null ? "" : e.getStatus().getDescription();
      throw switch (e.getStatus().getCode()) {
        case PERMISSION_DENIED -> new ApiException.Forbidden("NOT_AN_ARRANGER", description);
        case INVALID_ARGUMENT ->
            new ApiException.Unprocessable("TRIP_REQUEST_INVALID", description);
        case FAILED_PRECONDITION -> new ApiException.Conflict("TRIP_REQUEST_CONFLICT", description);
        default ->
            new ApiException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "TRAVEL_CORE_UNAVAILABLE",
                "Travel Core did not accept the trip request now ("
                    + e.getStatus().getCode()
                    + "); the candidate stays actionable, try again");
      };
    }
  }

  static boolean retryable(Status status) {
    return status.getCode() == Status.Code.UNAVAILABLE
        || status.getCode() == Status.Code.DEADLINE_EXCEEDED;
  }
}
