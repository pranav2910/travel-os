package io.travelos.travelcore.grpc;

import com.google.protobuf.Timestamp;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.travelos.common.money.Money;
import io.travelos.contracts.trip.v1.GetTripRequest;
import io.travelos.contracts.trip.v1.TransitionTripRequest;
import io.travelos.contracts.trip.v1.TravelCoreServiceGrpc;
import io.travelos.contracts.trip.v1.TravelIntent;
import io.travelos.contracts.trip.v1.TravelerSnapshot;
import io.travelos.contracts.trip.v1.Trip;
import io.travelos.contracts.trip.v1.TripStatus;
import io.travelos.spring.grpc.RequestContexts;
import io.travelos.spring.web.error.ApiException;
import io.travelos.travelcore.trip.TripService;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/** The workflow's door into Travel Core. */
@Component
public class TravelCoreGrpcService extends TravelCoreServiceGrpc.TravelCoreServiceImplBase {

  private final TripService trips;

  public TravelCoreGrpcService(TripService trips) {
    this.trips = trips;
  }

  @Override
  public void getTrip(GetTripRequest request, StreamObserver<Trip> observer) {
    RequestContexts.Validated ctx = RequestContexts.require(request.getCtx());
    try {
      io.travelos.travelcore.trip.Trip trip = trips.getInternal(ctx.tenant(), request.getTripId());
      Trip.Builder proto = toProto(trip).toBuilder();
      trips
          .latestApproval(ctx.tenant(), trip.tripId())
          .ifPresent(a -> proto.setApprovalStatus(a.status().name()));
      observer.onNext(proto.build());
    } catch (ApiException.NotFound e) {
      throw Status.NOT_FOUND.withDescription(e.getMessage()).asRuntimeException();
    }
    observer.onCompleted();
  }

  @Override
  public void transitionTrip(TransitionTripRequest request, StreamObserver<Trip> observer) {
    RequestContexts.Validated ctx = RequestContexts.require(request.getCtx());
    io.travelos.travelcore.trip.TripStatus to;
    try {
      to = io.travelos.travelcore.trip.TripStatus.valueOf(request.getTo().name());
    } catch (IllegalArgumentException e) {
      throw Status.INVALID_ARGUMENT
          .withDescription("unknown status " + request.getTo())
          .asRuntimeException();
    }
    if (to == io.travelos.travelcore.trip.TripStatus.FAILED
        && (request.getFailureStage().isBlank() || request.getFailureCode().isBlank())) {
      throw Status.INVALID_ARGUMENT
          .withDescription("FAILED needs failure_stage and failure_code")
          .asRuntimeException();
    }
    Money total =
        request.hasTotal()
            ? Money.of(request.getTotal().getCurrency(), request.getTotal().getAmountMinor())
            : null;
    try {
      io.travelos.travelcore.trip.Trip trip =
          trips.transition(
              ctx.tenant(),
              ctx.principal(),
              new TripService.Transition(
                  request.getTripId(),
                  to,
                  blankToNull(request.getReason()),
                  blankToNull(request.getSelectedBundleId()),
                  blankToNull(request.getOptimizationRunId()),
                  blankToNull(request.getPolicyDecisionId()),
                  blankToNull(request.getOrderId()),
                  total,
                  blankToNull(request.getApproverRole()),
                  blankToNull(request.getFailureStage()),
                  blankToNull(request.getFailureCode()),
                  blankToNull(request.getCtx().getCausationId())));
      observer.onNext(toProto(trip));
    } catch (ApiException.NotFound e) {
      throw Status.NOT_FOUND.withDescription(e.getMessage()).asRuntimeException();
    }
    observer.onCompleted();
  }

  static Trip toProto(io.travelos.travelcore.trip.Trip t) {
    Trip.Builder b =
        Trip.newBuilder()
            .setTripId(t.tripId())
            .setTenantId(t.tenantId().value())
            .setTravelerId(t.travelerId())
            .setStatus(TripStatus.valueOf(t.status().name()))
            .setSelectedBundleId(nullToEmpty(t.evidence().selectedBundleId()))
            .setOptimizationRunId(nullToEmpty(t.evidence().optimizationRunId()))
            .setPolicyDecisionId(nullToEmpty(t.evidence().policyDecisionId()))
            .setApprovalId(nullToEmpty(t.evidence().approvalId()))
            .setOrderId(nullToEmpty(t.evidence().orderId()))
            .setVersion(t.version())
            .setCreatedAt(ts(t.createdAt()))
            .setUpdatedAt(ts(t.updatedAt()))
            .setRequestText(nullToEmpty(t.requestText()))
            .setFailureStage(nullToEmpty(t.failureStage()))
            .setFailureCode(nullToEmpty(t.failureCode()))
            .setTraveler(
                TravelerSnapshot.newBuilder()
                    .setTravelerId(t.traveler().travelerId())
                    .setGivenName(t.traveler().givenName())
                    .setFamilyName(t.traveler().familyName())
                    .setEmail(t.traveler().email()));
    if (t.intent() != null) {
      io.travelos.travelcore.trip.TravelIntent i = t.intent();
      TravelIntent.Builder intent =
          TravelIntent.newBuilder()
              .setOrigin(i.origin())
              .setDestination(i.destination())
              .setEarliestDeparture(ts(i.earliestDeparture()))
              .setArrivalDeadline(ts(i.arrivalDeadline()))
              .setPurpose(nullToEmpty(i.purpose()))
              .setHotelRequired(i.hotelRequired())
              .setTravelers(i.travelers());
      if (i.returnAfter() != null) {
        intent.setReturnAfter(ts(i.returnAfter())).setLatestReturn(ts(i.latestReturn()));
      }
      b.setIntent(intent);
    }
    if (t.total() != null) {
      b.setTotal(
          io.travelos.contracts.common.v1.Money.newBuilder()
              .setCurrency(t.total().currency())
              .setAmountMinor(t.total().amountMinor()));
    }
    return b.build();
  }

  private static Timestamp ts(Instant i) {
    return Timestamp.newBuilder().setSeconds(i.getEpochSecond()).setNanos(i.getNano()).build();
  }

  private static String nullToEmpty(@Nullable String s) {
    return s == null ? "" : s;
  }

  private static @Nullable String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s;
  }
}
