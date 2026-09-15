package io.travelos.travelcore.trip;

import io.travelos.common.identity.Principal;
import io.travelos.common.money.Money;
import io.travelos.common.tenant.TenantId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Plain SQL through JdbcClient. Every statement takes the tenant id: a trip id alone never
 * identifies a row, so a leaked or guessed id is worthless across tenants.
 */
@Repository
public class TripRepository {

  private static final String COLUMNS =
      """
      trip_id, tenant_id, traveler_id, status, source, request_text,
      origin, destination, earliest_departure, arrival_deadline, return_after, latest_return,
      purpose, hotel_required, travelers,
      selected_bundle_id, optimization_run_id, policy_decision_id, approval_id, order_id,
      created_by, idempotency_key, request_fingerprint, version, created_at, updated_at,
      traveler_given_name, traveler_family_name, traveler_email, total_currency, total_minor,
      failure_stage, failure_code, explanation, itinerary
      """;

  private final JdbcClient jdbc;

  public TripRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public void insert(Trip trip) {
    TravelIntent intent = trip.intent();
    jdbc.sql(
            """
            INSERT INTO trip (trip_id, tenant_id, traveler_id, status, source, request_text,
              origin, destination, earliest_departure, arrival_deadline, return_after, latest_return,
              purpose, hotel_required, travelers,
              created_by, idempotency_key, request_fingerprint, version, created_at, updated_at,
              traveler_given_name, traveler_family_name, traveler_email, itinerary)
            VALUES (:tripId, :tenantId, :travelerId, :status, :source, :requestText,
              :origin, :destination, :earliestDeparture, :arrivalDeadline, :returnAfter, :latestReturn,
              :purpose, :hotelRequired, :travelers,
              :createdBy, :idempotencyKey, :requestFingerprint, :version, :createdAt, :updatedAt,
              :givenName, :familyName, :email, CAST(:itinerary AS jsonb))
            """)
        .param(
            "itinerary",
            intent == null || intent.itinerary() == null
                ? null
                : ItineraryCodec.toJson(intent.itinerary()))
        .param("tripId", trip.tripId())
        .param("tenantId", trip.tenantId().value())
        .param("travelerId", trip.travelerId())
        .param("status", trip.status().name())
        .param("source", trip.source().name())
        .param("requestText", trip.requestText())
        .param("origin", intent == null ? null : intent.origin())
        .param("destination", intent == null ? null : intent.destination())
        .param("earliestDeparture", ts(intent == null ? null : intent.earliestDeparture()))
        .param("arrivalDeadline", ts(intent == null ? null : intent.arrivalDeadline()))
        .param("returnAfter", ts(intent == null ? null : intent.returnAfter()))
        .param("latestReturn", ts(intent == null ? null : intent.latestReturn()))
        .param("purpose", intent == null ? null : intent.purpose())
        .param("hotelRequired", intent != null && intent.hotelRequired())
        .param("travelers", intent == null ? 1 : intent.travelers())
        .param("createdBy", trip.createdBy().id())
        .param("idempotencyKey", trip.idempotencyKey())
        .param("requestFingerprint", trip.requestFingerprint())
        .param("version", trip.version())
        .param("createdAt", ts(trip.createdAt()))
        .param("updatedAt", ts(trip.updatedAt()))
        .param("givenName", trip.traveler().givenName())
        .param("familyName", trip.traveler().familyName())
        .param("email", trip.traveler().email())
        .update();
  }

  public Optional<Trip> find(TenantId tenant, String tripId) {
    return jdbc.sql(
            "SELECT " + COLUMNS + " FROM trip WHERE tenant_id = :tenantId AND trip_id = :tripId")
        .param("tenantId", tenant.value())
        .param("tripId", tripId)
        .query(TripRepository::map)
        .optional();
  }

  public Optional<Trip> findByIdempotencyKey(TenantId tenant, String idempotencyKey) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM trip WHERE tenant_id = :tenantId AND idempotency_key = :key")
        .param("tenantId", tenant.value())
        .param("key", idempotencyKey)
        .query(TripRepository::map)
        .optional();
  }

  public List<Trip> listForTraveler(TenantId tenant, String travelerId, int limit) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM trip WHERE tenant_id = :tenantId AND traveler_id = :travelerId"
                + " ORDER BY created_at DESC, trip_id DESC LIMIT :limit")
        .param("tenantId", tenant.value())
        .param("travelerId", travelerId)
        .param("limit", limit)
        .query(TripRepository::map)
        .list();
  }

  /**
   * Optimistic concurrency: writes status, intent, evidence, total, failure and explanation only if
   * nobody else moved the trip since we read it.
   */
  public boolean update(Trip updated, long expectedVersion) {
    TripEvidence e = updated.evidence();
    TravelIntent intent = updated.intent();
    int rows =
        jdbc.sql(
                """
                UPDATE trip SET status = :status, version = :version, updated_at = :updatedAt,
                  origin = :origin, destination = :destination,
                  earliest_departure = :earliestDeparture, arrival_deadline = :arrivalDeadline,
                  return_after = :returnAfter, latest_return = :latestReturn, purpose = :purpose,
                  hotel_required = :hotelRequired, travelers = :travelers,
                  selected_bundle_id = :bundle, optimization_run_id = :optimizationRun,
                  policy_decision_id = :policyDecision, approval_id = :approval, order_id = :orderId,
                  total_currency = :currency, total_minor = :totalMinor,
                  failure_stage = :failureStage, failure_code = :failureCode,
                  explanation = :explanation, itinerary = CAST(:itinerary AS jsonb)
                WHERE tenant_id = :tenantId AND trip_id = :tripId AND version = :expectedVersion
                """)
            .param(
                "itinerary",
                intent == null || intent.itinerary() == null
                    ? null
                    : ItineraryCodec.toJson(intent.itinerary()))
            .param("origin", intent == null ? null : intent.origin())
            .param("destination", intent == null ? null : intent.destination())
            .param("earliestDeparture", ts(intent == null ? null : intent.earliestDeparture()))
            .param("arrivalDeadline", ts(intent == null ? null : intent.arrivalDeadline()))
            .param("returnAfter", ts(intent == null ? null : intent.returnAfter()))
            .param("latestReturn", ts(intent == null ? null : intent.latestReturn()))
            .param("purpose", intent == null ? null : intent.purpose())
            .param("hotelRequired", intent != null && intent.hotelRequired())
            .param("travelers", intent == null ? 1 : intent.travelers())
            .param("explanation", updated.explanation())
            .param("status", updated.status().name())
            .param("version", updated.version())
            .param("updatedAt", ts(updated.updatedAt()))
            .param("bundle", e.selectedBundleId())
            .param("optimizationRun", e.optimizationRunId())
            .param("policyDecision", e.policyDecisionId())
            .param("approval", e.approvalId())
            .param("orderId", e.orderId())
            .param("currency", updated.total() == null ? null : updated.total().currency())
            .param("totalMinor", updated.total() == null ? null : updated.total().amountMinor())
            .param("failureStage", updated.failureStage())
            .param("failureCode", updated.failureCode())
            .param("tenantId", updated.tenantId().value())
            .param("tripId", updated.tripId())
            .param("expectedVersion", expectedVersion)
            .update();
    return rows == 1;
  }

  public void appendHistory(
      Trip trip,
      @Nullable TripStatus from,
      TripStatus to,
      @Nullable String reason,
      Principal actor,
      Instant at) {
    jdbc.sql(
            """
            INSERT INTO trip_status_history
              (trip_id, tenant_id, from_status, to_status, reason, actor, occurred_at)
            VALUES (:tripId, :tenantId, :fromStatus, :toStatus, :reason, :actor, :occurredAt)
            """)
        .param("tripId", trip.tripId())
        .param("tenantId", trip.tenantId().value())
        .param("fromStatus", from == null ? null : from.name())
        .param("toStatus", to.name())
        .param("reason", reason)
        .param("actor", actor.id())
        .param("occurredAt", ts(at))
        .update();
  }

  public List<StatusChange> history(TenantId tenant, String tripId) {
    return jdbc.sql(
            """
            SELECT from_status, to_status, reason, actor, occurred_at
            FROM trip_status_history
            WHERE tenant_id = :tenantId AND trip_id = :tripId
            ORDER BY id
            """)
        .param("tenantId", tenant.value())
        .param("tripId", tripId)
        .query(
            (rs, rowNum) ->
                new StatusChange(
                    rs.getString("from_status") == null
                        ? null
                        : TripStatus.valueOf(rs.getString("from_status")),
                    TripStatus.valueOf(rs.getString("to_status")),
                    rs.getString("reason"),
                    rs.getString("actor"),
                    instant(rs, "occurred_at")))
        .list();
  }

  public record StatusChange(
      @Nullable TripStatus from,
      TripStatus to,
      @Nullable String reason,
      String actor,
      Instant occurredAt) {}

  private static Trip map(ResultSet rs, int rowNum) throws SQLException {
    TravelIntent intent =
        rs.getString("origin") == null
            ? null
            : new TravelIntent(
                rs.getString("origin"),
                rs.getString("destination"),
                instant(rs, "earliest_departure"),
                instant(rs, "arrival_deadline"),
                instant(rs, "return_after"),
                instant(rs, "latest_return"),
                rs.getString("purpose"),
                rs.getBoolean("hotel_required"),
                rs.getInt("travelers"),
                ItineraryCodec.fromJson(rs.getString("itinerary")));
    String currency = rs.getString("total_currency");
    Money total = currency == null ? null : Money.of(currency, rs.getLong("total_minor"));
    return new Trip(
        rs.getString("trip_id"),
        TenantId.of(rs.getString("tenant_id")),
        rs.getString("traveler_id"),
        TripStatus.valueOf(rs.getString("status")),
        TripSource.valueOf(rs.getString("source")),
        rs.getString("request_text"),
        intent,
        new TripEvidence(
            rs.getString("selected_bundle_id"),
            rs.getString("optimization_run_id"),
            rs.getString("policy_decision_id"),
            rs.getString("approval_id"),
            rs.getString("order_id")),
        Principal.parse(rs.getString("created_by")),
        rs.getString("idempotency_key"),
        rs.getString("request_fingerprint"),
        rs.getLong("version"),
        instant(rs, "created_at"),
        instant(rs, "updated_at"),
        new TravelerSnapshot(
            rs.getString("traveler_id"),
            rs.getString("traveler_given_name"),
            rs.getString("traveler_family_name"),
            rs.getString("traveler_email")),
        total,
        rs.getString("failure_stage"),
        rs.getString("failure_code"),
        rs.getString("explanation"));
  }

  private static @Nullable Instant instant(ResultSet rs, String column) throws SQLException {
    OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }

  private static @Nullable OffsetDateTime ts(@Nullable Instant instant) {
    return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }
}
