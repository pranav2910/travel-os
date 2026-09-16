package io.travelos.context;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.travelos.contracts.trip.v1.CreateTripRequest;
import io.travelos.contracts.trip.v1.TravelCoreServiceGrpc;
import io.travelos.contracts.trip.v1.Trip;
import io.travelos.contracts.trip.v1.TripStatus;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** A scripted Travel Core: creates trips, idempotent by key, remembers every request. */
final class FakeTravelCore extends TravelCoreServiceGrpc.TravelCoreServiceImplBase {
  final List<CreateTripRequest> requests = new CopyOnWriteArrayList<>();
  final Map<String, String> tripsByKey = new ConcurrentHashMap<>();
  final AtomicInteger counter = new AtomicInteger();
  volatile boolean unavailable;
  private Server server;

  int start() throws IOException {
    server = ServerBuilder.forPort(0).addService(this).build().start();
    return server.getPort();
  }

  void stop() {
    if (server != null) {
      server.shutdownNow();
    }
  }

  @Override
  public synchronized void createTrip(CreateTripRequest request, StreamObserver<Trip> observer) {
    if (unavailable) {
      observer.onError(Status.UNAVAILABLE.withDescription("travel core down").asRuntimeException());
      return;
    }
    if (!request.getActorRolesList().contains("TRAVEL_ADMIN")
        && !request.getActorRolesList().contains("MANAGER")
        && !request.getActorEmployeeId().equals(request.getTravelerId())) {
      observer.onError(
          Status.PERMISSION_DENIED.withDescription("NOT_AN_ARRANGER").asRuntimeException());
      return;
    }
    requests.add(request);
    String key = request.getCtx().getTenantId() + "|" + request.getCtx().getIdempotencyKey();
    String tripId =
        tripsByKey.computeIfAbsent(
            key, k -> io.travelos.common.ids.Ids.newId(io.travelos.common.ids.IdPrefix.TRIP));
    observer.onNext(
        Trip.newBuilder()
            .setTripId(tripId)
            .setTenantId(request.getCtx().getTenantId())
            .setTravelerId(request.getTravelerId())
            .setStatus(TripStatus.SUBMITTED)
            .setIntent(request.getIntent())
            .build());
    observer.onCompleted();
  }
}
