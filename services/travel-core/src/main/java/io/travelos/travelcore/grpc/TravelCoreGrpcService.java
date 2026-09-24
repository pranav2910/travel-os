package io.travelos.travelcore.grpc;

import com.google.protobuf.Timestamp;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.travelos.common.money.Money;
import io.travelos.contracts.common.v1.ModelCall;
import io.travelos.contracts.trip.v1.ApplyIntentExtractionRequest;
import io.travelos.contracts.trip.v1.ComponentState;
import io.travelos.contracts.trip.v1.CreateTripRequest;
import io.travelos.contracts.trip.v1.GetTripRequest;
import io.travelos.contracts.trip.v1.Itinerary;
import io.travelos.contracts.trip.v1.Leg;
import io.travelos.contracts.trip.v1.Stay;
import io.travelos.contracts.trip.v1.Transfer;
import io.travelos.contracts.trip.v1.TransitionTripRequest;
import io.travelos.contracts.trip.v1.TravelCoreServiceGrpc;
import io.travelos.contracts.trip.v1.TravelIntent;
import io.travelos.contracts.trip.v1.TravelerSnapshot;
import io.travelos.contracts.trip.v1.Trip;
import io.travelos.contracts.trip.v1.TripStatus;
import io.travelos.contracts.trip.v1.UpdateComponentsRequest;
import io.travelos.spring.grpc.RequestContexts;
import io.travelos.spring.web.auth.RequestPrincipal;
import io.travelos.spring.web.error.ApiException;
import io.travelos.travelcore.trip.AgentDecision;
import io.travelos.travelcore.trip.TripComponent;
import io.travelos.travelcore.trip.TripService;
import io.travelos.travelcore.trip.TripSource;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
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
      Trip.Builder proto = withComponents(ctx.tenant(), trip).toBuilder();
      trips
          .latestApproval(ctx.tenant(), trip.tripId())
          .ifPresent(a -> proto.setApprovalStatus(a.status().name()));
      observer.onNext(proto.build());
    } catch (ApiException.NotFound e) {
      throw Status.NOT_FOUND.withDescription(e.getMessage()).asRuntimeException();
    }
    observer.onCompleted();
  }

  /**
   * Slice 4: a trusted service (Enterprise Context) creates a trip for a person it authenticated.
   * The person's roles and employee id travel with the request; Travel Core applies the same
   * arranger rule it applies at the API. Idempotent by ctx.idempotency_key within the tenant.
   */
  @Override
  public void createTrip(CreateTripRequest request, StreamObserver<Trip> observer) {
    RequestContexts.Validated ctx = RequestContexts.require(request.getCtx());
    if (request.getCtx().getIdempotencyKey().isBlank()) {
      throw Status.INVALID_ARGUMENT
          .withDescription("ctx.idempotency_key is required")
          .asRuntimeException();
    }
    if (!request.hasIntent()) {
      throw Status.INVALID_ARGUMENT.withDescription("intent is required").asRuntimeException();
    }
    TripSource source;
    try {
      source =
          request.getSource().isBlank() ? TripSource.API : TripSource.valueOf(request.getSource());
    } catch (IllegalArgumentException e) {
      throw Status.INVALID_ARGUMENT
          .withDescription("unknown source " + request.getSource())
          .asRuntimeException();
    }
    RequestPrincipal me =
        new RequestPrincipal(
            ctx.principal(),
            ctx.tenant(),
            blankToNull(request.getActorEmployeeId()),
            new java.util.HashSet<>(request.getActorRolesList()),
            null,
            null,
            null);
    TripService.CreateTrip command;
    try {
      command =
          new TripService.CreateTrip(
              blankToNull(request.getTravelerId()),
              source,
              blankToNull(request.getRequestText()),
              fromSpecs(request.getIntent()).withExplicitStay(),
              request.hasTraveler()
                  ? new io.travelos.travelcore.trip.TravelerIdentity(
                      blankToNull(request.getTraveler().getGivenName()),
                      blankToNull(request.getTraveler().getFamilyName()),
                      blankToNull(request.getTraveler().getEmail()))
                  : null,
              blankToNull(request.getProjectId()));
    } catch (io.travelos.travelcore.trip.IntentRejectedException e) {
      throw Status.INVALID_ARGUMENT
          .withDescription(e.code() + ": " + e.getMessage())
          .asRuntimeException();
    } catch (IllegalArgumentException e) {
      throw Status.INVALID_ARGUMENT
          .withDescription("INTENT_INVALID: " + e.getMessage())
          .asRuntimeException();
    }
    io.travelos.travelcore.trip.Trip trip;
    try {
      trip =
          trips.create(
              me,
              command,
              request.getCtx().getIdempotencyKey(),
              blankToNull(request.getSourceReference()));
    } catch (ApiException.Forbidden e) {
      throw Status.PERMISSION_DENIED.withDescription(e.getMessage()).asRuntimeException();
    } catch (io.travelos.travelcore.trip.IntentRejectedException e) {
      throw Status.INVALID_ARGUMENT
          .withDescription(e.code() + ": " + e.getMessage())
          .asRuntimeException();
    } catch (ApiException.Unprocessable e) {
      throw Status.FAILED_PRECONDITION
          .withDescription(e.code() + ": " + e.getMessage())
          .asRuntimeException();
    }
    observer.onNext(withComponents(ctx.tenant(), trip));
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
                  blankToNull(request.getCtx().getCausationId()),
                  blankToNull(request.getExplanation()),
                  blankToNull(request.getReplanReason())));
      observer.onNext(withComponents(ctx.tenant(), trip));
    } catch (ApiException.NotFound e) {
      throw Status.NOT_FOUND.withDescription(e.getMessage()).asRuntimeException();
    }
    observer.onCompleted();
  }

  @Override
  public void applyIntentExtraction(
      ApplyIntentExtractionRequest request, StreamObserver<Trip> observer) {
    RequestContexts.Validated ctx = RequestContexts.require(request.getCtx());
    io.travelos.travelcore.trip.TravelIntent intent;
    try {
      intent = request.hasIntent() ? fromProto(request.getIntent()) : null;
    } catch (IllegalArgumentException e) {
      throw Status.INVALID_ARGUMENT
          .withDescription("intent: " + e.getMessage())
          .asRuntimeException();
    }
    ModelCall c = request.getCall();
    AgentDecision.ModelCallEvidence call =
        request.hasCall()
            ? new AgentDecision.ModelCallEvidence(
                c.getCallId(),
                c.getProvider(),
                c.getModel(),
                c.getPromptId(),
                c.getPromptVersion(),
                blankToNull(c.getProviderRequestId()),
                c.getInputTokens(),
                c.getOutputTokens(),
                c.getCacheReadTokens(),
                c.getLatencyMs(),
                c.getCostMicros(),
                c.hasCalledAt()
                    ? Instant.ofEpochSecond(
                        c.getCalledAt().getSeconds(), c.getCalledAt().getNanos())
                    : null)
            : null;
    try {
      io.travelos.travelcore.trip.Trip trip =
          trips.applyIntentExtraction(
              ctx.tenant(),
              ctx.principal(),
              new TripService.IntentExtraction(
                  request.getTripId(),
                  request.getResult(),
                  intent,
                  request.getMissingFieldsList(),
                  blankToNull(request.getClarifyingQuestion()),
                  request.getAssumptionsList(),
                  request.getConfidence(),
                  call,
                  blankToNull(request.getCtx().getCausationId())));
      observer.onNext(toProto(trip));
    } catch (ApiException.NotFound e) {
      throw Status.NOT_FOUND.withDescription(e.getMessage()).asRuntimeException();
    } catch (IllegalArgumentException e) {
      throw Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException();
    } catch (IllegalStateException e) {
      throw Status.FAILED_PRECONDITION.withDescription(e.getMessage()).asRuntimeException();
    }
    observer.onCompleted();
  }

  @Override
  public void updateComponents(UpdateComponentsRequest request, StreamObserver<Trip> observer) {
    RequestContexts.Validated ctx = RequestContexts.require(request.getCtx());
    List<TripComponent> states = new ArrayList<>();
    Instant now = Instant.now();
    int position = 0;
    for (ComponentState c : request.getComponentsList()) {
      if (c.getComponentId().isBlank() || c.getType().isBlank() || c.getStatus().isBlank()) {
        throw Status.INVALID_ARGUMENT
            .withDescription("every component needs component_id, type and status")
            .asRuntimeException();
      }
      states.add(
          new TripComponent(
              c.getComponentId(),
              c.getType(),
              c.getStatus(),
              blankToNull(c.getOfferId()),
              blankToNull(c.getProvider()),
              blankToNull(c.getExternalRef()),
              c.hasTotal()
                  ? Money.of(c.getTotal().getCurrency(), c.getTotal().getAmountMinor())
                  : null,
              blankToNull(c.getFailureCode()),
              blankToNull(c.getSummary()),
              position++,
              c.hasUpdatedAt() ? instant(c.getUpdatedAt()) : now));
    }
    try {
      io.travelos.travelcore.trip.Trip trip =
          trips.updateComponents(ctx.tenant(), request.getTripId(), states);
      observer.onNext(withComponents(ctx.tenant(), trip));
    } catch (ApiException.NotFound e) {
      throw Status.NOT_FOUND.withDescription(e.getMessage()).asRuntimeException();
    }
    observer.onCompleted();
  }

  private Trip withComponents(
      io.travelos.common.tenant.TenantId tenant, io.travelos.travelcore.trip.Trip trip) {
    Trip.Builder b = toProto(trip).toBuilder();
    trips
        .allocation(tenant, trip.tripId())
        .ifPresent(
            a ->
                b.setAllocation(
                    io.travelos.contracts.trip.v1.TripAllocation.newBuilder()
                        .setDepartmentId(nullToEmpty(a.departmentId()))
                        .setCostCenterId(nullToEmpty(a.costCenterId()))
                        .setLegalEntityId(nullToEmpty(a.legalEntityId()))
                        .setOfficeId(nullToEmpty(a.officeId()))
                        .setProjectId(nullToEmpty(a.projectId()))
                        .setProjectRestricted(a.projectRestricted())
                        .setManagerEmployeeId(nullToEmpty(a.managerEmployeeId()))));
    for (TripComponent c : trips.components(tenant, trip.tripId())) {
      b.addComponents(toProto(c));
    }
    return b.build();
  }

  static ComponentState toProto(TripComponent c) {
    ComponentState.Builder b =
        ComponentState.newBuilder()
            .setComponentId(c.componentId())
            .setType(c.type())
            .setStatus(c.status())
            .setOfferId(nullToEmpty(c.offerId()))
            .setProvider(nullToEmpty(c.provider()))
            .setExternalRef(nullToEmpty(c.externalRef()))
            .setFailureCode(nullToEmpty(c.failureCode()))
            .setSummary(nullToEmpty(c.summary()))
            .setUpdatedAt(ts(c.updatedAt()));
    if (c.total() != null) {
      b.setTotal(
          io.travelos.contracts.common.v1.Money.newBuilder()
              .setCurrency(c.total().currency())
              .setAmountMinor(c.total().amountMinor()));
    }
    return b.build();
  }

  static io.travelos.travelcore.trip.TravelIntent fromProto(TravelIntent p) {
    if (p.hasItinerary()) {
      return io.travelos.travelcore.trip.TravelIntent.of(
          fromProto(p.getItinerary()),
          blankToNull(p.getPurpose()),
          p.getTravelers() == 0 ? 1 : p.getTravelers());
    }
    return new io.travelos.travelcore.trip.TravelIntent(
        p.getOrigin(),
        p.getDestination(),
        instant(p.getEarliestDeparture()),
        instant(p.getArrivalDeadline()),
        p.hasReturnAfter() ? instant(p.getReturnAfter()) : null,
        p.hasLatestReturn() ? instant(p.getLatestReturn()) : null,
        blankToNull(p.getPurpose()),
        p.getHotelRequired(),
        p.getTravelers() == 0 ? 1 : p.getTravelers());
  }

  /**
   * An external caller's intent: legs, stays and transfers as specs, so component ids are minted
   * and zones derived here (the caller need not know either), exactly like the REST API.
   */
  static io.travelos.travelcore.trip.TravelIntent fromSpecs(TravelIntent p) {
    if (!p.hasItinerary()) {
      return fromProto(p);
    }
    Itinerary it = p.getItinerary();
    List<io.travelos.travelcore.trip.Itinerary.LegSpec> legs = new ArrayList<>();
    for (Leg l : it.getLegsList()) {
      legs.add(
          new io.travelos.travelcore.trip.Itinerary.LegSpec(
              blankToNull(l.getComponentId()),
              l.getOrigin(),
              l.getDestination(),
              instant(l.getEarliestDeparture()),
              instant(l.getArrivalDeadline())));
    }
    List<io.travelos.travelcore.trip.Itinerary.StaySpec> stays = new ArrayList<>();
    for (Stay st : it.getStaysList()) {
      stays.add(
          new io.travelos.travelcore.trip.Itinerary.StaySpec(
              blankToNull(st.getComponentId()),
              st.getCity(),
              java.time.LocalDate.parse(st.getCheckInDate()),
              java.time.LocalDate.parse(st.getCheckOutDate()),
              st.getRequired()));
    }
    List<io.travelos.travelcore.trip.Itinerary.TransferSpec> transfers = new ArrayList<>();
    for (Transfer t : it.getTransfersList()) {
      transfers.add(
          new io.travelos.travelcore.trip.Itinerary.TransferSpec(
              blankToNull(t.getComponentId()),
              t.getKind(),
              t.getCity(),
              blankToNull(t.getFromLocation()),
              blankToNull(t.getToLocation()),
              t.hasPickup() ? instant(t.getPickup()) : null,
              t.getRequired()));
    }
    return io.travelos.travelcore.trip.TravelIntent.of(
        io.travelos.travelcore.trip.Itinerary.of(
            legs, stays, transfers, blankToNull(it.getCurrency())),
        blankToNull(p.getPurpose()),
        p.getTravelers() == 0 ? 1 : p.getTravelers());
  }

  private static Instant instant(Timestamp ts) {
    return Instant.ofEpochSecond(ts.getSeconds(), ts.getNanos());
  }

  static io.travelos.travelcore.trip.Itinerary fromProto(Itinerary p) {
    List<io.travelos.travelcore.trip.Itinerary.Leg> legs = new ArrayList<>();
    for (Leg l : p.getLegsList()) {
      legs.add(
          new io.travelos.travelcore.trip.Itinerary.Leg(
              l.getComponentId(),
              l.getSequence(),
              l.getOrigin(),
              l.getDestination(),
              instant(l.getEarliestDeparture()),
              instant(l.getArrivalDeadline()),
              ZoneId.of(l.getOriginTimeZone()),
              ZoneId.of(l.getDestinationTimeZone()),
              l.getDependsOnList()));
    }
    List<io.travelos.travelcore.trip.Itinerary.Stay> stays = new ArrayList<>();
    for (Stay st : p.getStaysList()) {
      stays.add(
          new io.travelos.travelcore.trip.Itinerary.Stay(
              st.getComponentId(),
              st.getCity(),
              LocalDate.parse(st.getCheckInDate()),
              LocalDate.parse(st.getCheckOutDate()),
              ZoneId.of(st.getTimeZone()),
              st.getRequired(),
              st.getDependsOnList()));
    }
    List<io.travelos.travelcore.trip.Itinerary.Transfer> transfers = new ArrayList<>();
    for (Transfer t : p.getTransfersList()) {
      transfers.add(
          new io.travelos.travelcore.trip.Itinerary.Transfer(
              t.getComponentId(),
              t.getKind(),
              t.getCity(),
              t.getFromLocation(),
              t.getToLocation(),
              t.hasPickup() ? instant(t.getPickup()) : null,
              ZoneId.of(t.getTimeZone()),
              t.getRequired(),
              t.getDependsOnList()));
    }
    return new io.travelos.travelcore.trip.Itinerary(legs, stays, transfers, p.getCurrency());
  }

  static Itinerary toProto(io.travelos.travelcore.trip.Itinerary it) {
    Itinerary.Builder b = Itinerary.newBuilder().setCurrency(it.currency());
    for (io.travelos.travelcore.trip.Itinerary.Leg l : it.legs()) {
      b.addLegs(
          Leg.newBuilder()
              .setComponentId(l.componentId())
              .setSequence(l.sequence())
              .setOrigin(l.origin())
              .setDestination(l.destination())
              .setEarliestDeparture(ts(l.earliestDeparture()))
              .setArrivalDeadline(ts(l.arrivalDeadline()))
              .setOriginTimeZone(l.originZone().getId())
              .setDestinationTimeZone(l.destinationZone().getId())
              .addAllDependsOn(l.dependsOn()));
    }
    for (io.travelos.travelcore.trip.Itinerary.Stay st : it.stays()) {
      b.addStays(
          Stay.newBuilder()
              .setComponentId(st.componentId())
              .setCity(st.city())
              .setCheckInDate(st.checkIn().toString())
              .setCheckOutDate(st.checkOut().toString())
              .setTimeZone(st.zone().getId())
              .setNights(st.nights())
              .setRequired(st.required())
              .addAllDependsOn(st.dependsOn()));
    }
    for (io.travelos.travelcore.trip.Itinerary.Transfer t : it.transfers()) {
      Transfer.Builder tb =
          Transfer.newBuilder()
              .setComponentId(t.componentId())
              .setKind(t.kind())
              .setCity(t.city())
              .setFromLocation(t.from())
              .setToLocation(t.to())
              .setTimeZone(t.zone().getId())
              .setRequired(t.required())
              .addAllDependsOn(t.dependsOn());
      if (t.pickup() != null) {
        tb.setPickup(ts(t.pickup()));
      }
      b.addTransfers(tb);
    }
    return b.build();
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
            .setExplanation(nullToEmpty(t.explanation()))
            .setTraveler(
                TravelerSnapshot.newBuilder()
                    .setTravelerId(t.traveler().travelerId())
                    .setGivenName(t.traveler().givenName())
                    .setFamilyName(t.traveler().familyName())
                    .setEmail(t.traveler().email())
                    .setKind(t.travelerId().startsWith("gst_") ? "GUEST" : "EMPLOYEE"));
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
      if (i.itinerary() != null) {
        intent.setItinerary(toProto(i.itinerary()));
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
