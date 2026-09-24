package io.travelos.supplier.ledger;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class MutationAttemptRepository {
  private final JdbcClient jdbc;

  public MutationAttemptRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** 1 when this call owns the attempt; 0 when the key already has one. */
  public int insertStarted(MutationAttempt a) {
    return jdbc.sql(
            """
            INSERT INTO supplier_mutation_attempt (attempt_id, tenant_id, provider, command, idempotency_key, request_digest,
              status, calls, correlation_id, created_at, updated_at)
            VALUES (:id, :t, :p, :c, :k, :d, 'STARTED', 1, :corr, :now, :now)
            ON CONFLICT ON CONSTRAINT supplier_mutation_attempt_key DO NOTHING
            """)
        .param("id", a.attemptId())
        .param("t", a.tenantId())
        .param("p", a.provider())
        .param("c", a.command().name())
        .param("k", a.idempotencyKey())
        .param("d", a.requestDigest())
        .param("corr", a.correlationId())
        .param("now", Timestamp.from(a.createdAt()))
        .update();
  }

  public Optional<MutationAttempt> find(
      String tenant, String provider, MutationAttempt.Command command, String key) {
    return jdbc.sql(
            "SELECT * FROM supplier_mutation_attempt WHERE tenant_id = :t AND provider = :p AND command = :c AND idempotency_key = :k")
        .param("t", tenant)
        .param("p", provider)
        .param("c", command.name())
        .param("k", key)
        .query(MutationAttemptRepository::map)
        .optional();
  }

  public void succeeded(
      String attemptId, @Nullable String externalRef, byte[] response, Instant now) {
    jdbc.sql(
            "UPDATE supplier_mutation_attempt SET status = 'SUCCEEDED', external_ref = :ref, response = :r, failure_code = NULL, failure_message = NULL, updated_at = :now WHERE attempt_id = :id")
        .param("ref", externalRef)
        .param("r", response)
        .param("now", Timestamp.from(now))
        .param("id", attemptId)
        .update();
  }

  public void failed(String attemptId, String code, String message, Instant now) {
    jdbc.sql(
            "UPDATE supplier_mutation_attempt SET status = 'FAILED', failure_code = :c, failure_message = :m, updated_at = :now WHERE attempt_id = :id")
        .param("c", code)
        .param("m", message.length() > 1000 ? message.substring(0, 1000) : message)
        .param("now", Timestamp.from(now))
        .param("id", attemptId)
        .update();
  }

  public void unknown(String attemptId, String code, String message, Instant now) {
    jdbc.sql(
            "UPDATE supplier_mutation_attempt SET status = 'UNKNOWN', failure_code = :c, failure_message = :m, updated_at = :now WHERE attempt_id = :id")
        .param("c", code)
        .param("m", message.length() > 1000 ? message.substring(0, 1000) : message)
        .param("now", Timestamp.from(now))
        .param("id", attemptId)
        .update();
  }

  public void anotherCall(String attemptId, Instant now) {
    jdbc.sql(
            "UPDATE supplier_mutation_attempt SET calls = calls + 1, status = 'STARTED', updated_at = :now WHERE attempt_id = :id")
        .param("now", Timestamp.from(now))
        .param("id", attemptId)
        .update();
  }

  /** Attempts whose outcome is not known: the reconciliation worklist. */
  public List<MutationAttempt> open(int limit) {
    return jdbc.sql(
            "SELECT * FROM supplier_mutation_attempt WHERE status IN ('STARTED', 'UNKNOWN') ORDER BY updated_at LIMIT :n")
        .param("n", limit)
        .query(MutationAttemptRepository::map)
        .list();
  }

  private static MutationAttempt map(ResultSet rs, int i) throws SQLException {
    return new MutationAttempt(
        rs.getString("attempt_id"),
        rs.getString("tenant_id"),
        rs.getString("provider"),
        MutationAttempt.Command.valueOf(rs.getString("command")),
        rs.getString("idempotency_key"),
        rs.getString("request_digest"),
        MutationAttempt.Status.valueOf(rs.getString("status")),
        rs.getString("external_ref"),
        rs.getBytes("response"),
        rs.getString("failure_code"),
        rs.getString("failure_message"),
        rs.getInt("calls"),
        rs.getString("correlation_id"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        rs.getObject("updated_at", OffsetDateTime.class).toInstant());
  }
}
