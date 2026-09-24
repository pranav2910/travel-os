package io.travelos.travelcore.api;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.travelos.contracts.context.v1.ArrangerAuthorization;
import io.travelos.contracts.context.v1.AuthorizeArrangerRequest;
import io.travelos.contracts.context.v1.EnterpriseContextServiceGrpc;
import io.travelos.contracts.context.v1.GetTravelerSnapshotRequest;
import io.travelos.contracts.context.v1.OrgAllocation;
import io.travelos.contracts.context.v1.PassengerSnapshot;
import io.travelos.contracts.context.v1.TravelerSnapshotResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A scripted Enterprise Context with the ADR-0014 rules in miniature: a directory of employees with
 * their HRIS manager, explicit arranger grants, one restricted project with members. It decides
 * exactly what the real service would, from its own records, never from the request.
 */
final class FakeEnterpriseContext
    extends EnterpriseContextServiceGrpc.EnterpriseContextServiceImplBase {
  record Person(
      String givenName, String familyName, String email, String manager, String department) {}

  static final String PROJECT = "prj_01ARZ3NDEKTSV4RRFFQ69G5FAA";
  final Map<String, Person> people =
      Map.of(
          "emp_1001", new Person("Alice", "Nguyen", "alice@acme.example", "emp_1002", "dept_eng"),
          "emp_1002", new Person("Bob", "Chen", "bob@acme.example", "", "dept_eng"),
          "emp_1003", new Person("Carol", "Diaz", "carol@acme.example", "", "dept_ops"),
          "emp_1004", new Person("Dan", "Ola", "dan@acme.example", "emp_1002", "dept_eng"),
          "emp_1005", new Person("Erin", "Assist", "erin@acme.example", "emp_1003", "dept_ops"),
          "emp_1006", new Person("Frank", "Other", "frank@acme.example", "", "dept_sales"));

  /** arranger -> travelers they hold an EMPLOYEE-scoped grant for */
  final Map<String, Set<String>> grants = Map.of("emp_1005", Set.of("emp_1001"));

  final Set<String> projectMembers = Set.of("emp_1004");
  final List<GetTravelerSnapshotRequest> snapshots = new CopyOnWriteArrayList<>();
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

  private String basis(String arranger, Set<String> roles, String traveler) {
    Person p = people.get(traveler);
    if (p == null) {
      return null;
    }
    if (arranger.equals(traveler)) return "SELF";
    if (roles.contains("TRAVEL_ADMIN")) return "TRAVEL_ADMIN";
    if (grants.getOrDefault(arranger, Set.of()).contains(traveler)) return "GRANT";
    if (arranger.equals(p.manager())) return "MANAGER";
    return null;
  }

  @Override
  public void authorizeArranger(
      AuthorizeArrangerRequest r, StreamObserver<ArrangerAuthorization> o) {
    if (unavailable) {
      o.onError(Status.UNAVAILABLE.withDescription("context down").asRuntimeException());
      return;
    }
    Set<String> roles = Set.copyOf(r.getArrangerRolesList());
    ArrangerAuthorization.Builder b = ArrangerAuthorization.newBuilder();
    if (!people.containsKey(r.getTravelerId())) {
      b.setAllowed(false).setReasonCode("TRAVELER_UNKNOWN");
    } else {
      String basis = basis(r.getArrangerEmployeeId(), roles, r.getTravelerId());
      if (basis == null) {
        b.setAllowed(false).setReasonCode("NOT_AN_ARRANGER");
      } else if (!r.getProjectId().isBlank()) {
        boolean travelerIn = projectMembers.contains(r.getTravelerId());
        boolean arrangerIn =
            basis.equals("SELF")
                || basis.equals("MANAGER")
                || basis.equals("TRAVEL_ADMIN")
                || projectMembers.contains(r.getArrangerEmployeeId());
        if (!r.getProjectId().equals(PROJECT)) {
          b.setAllowed(false).setReasonCode("PROJECT_UNKNOWN");
        } else if (!travelerIn || !arrangerIn) {
          b.setAllowed(false).setReasonCode("PROJECT_RESTRICTED");
        } else {
          b.setAllowed(true).setBasis(basis);
        }
      } else {
        b.setAllowed(true).setBasis(basis);
      }
    }
    o.onNext(b.build());
    o.onCompleted();
  }

  @Override
  public void getTravelerSnapshot(
      GetTravelerSnapshotRequest r, StreamObserver<TravelerSnapshotResponse> o) {
    if (unavailable) {
      o.onError(Status.UNAVAILABLE.withDescription("context down").asRuntimeException());
      return;
    }
    Person p = people.get(r.getTravelerId());
    if (p == null
        || basis(r.getArrangerEmployeeId(), Set.copyOf(r.getArrangerRolesList()), r.getTravelerId())
            == null) {
      o.onError(
          Status.NOT_FOUND.withDescription("traveler " + r.getTravelerId()).asRuntimeException());
      return;
    }
    snapshots.add(r);
    o.onNext(
        TravelerSnapshotResponse.newBuilder()
            .setPassenger(
                PassengerSnapshot.newBuilder()
                    .setTravelerId(r.getTravelerId())
                    .setKind("EMPLOYEE")
                    .setGivenName(p.givenName())
                    .setFamilyName(p.familyName())
                    .setEmail(p.email())
                    .setActive(true)
                    .setProfileVersion("3"))
            .setAllocation(
                OrgAllocation.newBuilder()
                    .setDepartmentId(p.department())
                    .setManagerEmployeeId(p.manager())
                    .setProjectId(r.getProjectId())
                    .setProjectRestricted(PROJECT.equals(r.getProjectId())))
            .build());
    o.onCompleted();
  }
}
