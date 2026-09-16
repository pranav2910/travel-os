package io.travelos.context.store;

import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * What the SIMULATED enterprise systems currently hold, and the faults they are told to simulate. A
 * live provider would be an HTTP client; the sandbox is a table with the same paging contract.
 */
@Repository
public class SandboxStore {
  public record Item(
      long seq, String sourceId, long revision, boolean deleted, String payloadJson) {}

  public record Faults(int unavailableCalls, int rateLimitPage, int rateLimitHits) {}

  private final JdbcClient jdbc;

  public SandboxStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public long append(
      String tenantId,
      String connectorId,
      String sourceId,
      long revision,
      boolean deleted,
      String payloadJson,
      Instant now) {
    return jdbc.sql(
            """
            INSERT INTO sandbox_item (tenant_id, connector_id, source_id, revision, deleted, payload, created_at)
            VALUES (:t, :c, :s, :r, :d, CAST(:p AS jsonb), :now) RETURNING seq
            """)
        .param("t", tenantId)
        .param("c", connectorId)
        .param("s", sourceId)
        .param("r", revision)
        .param("d", deleted)
        .param("p", payloadJson)
        .param("now", Rows.ts(now))
        .query(Long.class)
        .single();
  }

  /** Everything after the watermark, in the order the source recorded it, one page at a time. */
  public List<Item> page(String connectorId, long afterSeq, int offset, int limit) {
    return jdbc.sql(
            """
            SELECT seq, source_id, revision, deleted, payload FROM sandbox_item
            WHERE connector_id = :c AND seq > :after ORDER BY seq LIMIT :limit OFFSET :offset
            """)
        .param("c", connectorId)
        .param("after", afterSeq)
        .param("limit", limit)
        .param("offset", offset)
        .query(
            (rs, i) ->
                new Item(
                    rs.getLong("seq"),
                    rs.getString("source_id"),
                    rs.getLong("revision"),
                    rs.getBoolean("deleted"),
                    rs.getString("payload")))
        .list();
  }

  public long count(String connectorId, long afterSeq) {
    Long n =
        jdbc.sql("SELECT count(*) FROM sandbox_item WHERE connector_id = :c AND seq > :after")
            .param("c", connectorId)
            .param("after", afterSeq)
            .query(Long.class)
            .single();
    return n == null ? 0 : n;
  }

  public void setFaults(String connectorId, int unavailableCalls, int rateLimitPage, Instant now) {
    jdbc.sql(
            """
            INSERT INTO sandbox_fault (connector_id, unavailable_calls, rate_limit_page, rate_limit_hits, updated_at)
            VALUES (:c, :u, :p, 0, :now)
            ON CONFLICT (connector_id) DO UPDATE SET unavailable_calls = EXCLUDED.unavailable_calls,
              rate_limit_page = EXCLUDED.rate_limit_page, rate_limit_hits = 0, updated_at = EXCLUDED.updated_at
            """)
        .param("c", connectorId)
        .param("u", unavailableCalls)
        .param("p", rateLimitPage)
        .param("now", Rows.ts(now))
        .update();
  }

  public Faults faults(String connectorId) {
    return jdbc.sql("SELECT * FROM sandbox_fault WHERE connector_id = :c")
        .param("c", connectorId)
        .query(
            (rs, i) ->
                new Faults(
                    rs.getInt("unavailable_calls"),
                    rs.getInt("rate_limit_page"),
                    rs.getInt("rate_limit_hits")))
        .optional()
        .orElse(new Faults(0, -1, 0));
  }

  /** Consumes one simulated outage; true when the call must fail. Runs in its own transaction. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean consumeUnavailable(String connectorId) {
    return jdbc.sql(
                """
                UPDATE sandbox_fault SET unavailable_calls = unavailable_calls - 1
                WHERE connector_id = :c AND unavailable_calls > 0
                """)
            .param("c", connectorId)
            .update()
        == 1;
  }

  /** The rate limit fires once for the configured page; true when this call must fail. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean consumeRateLimit(String connectorId, int page) {
    return jdbc.sql(
                """
                UPDATE sandbox_fault SET rate_limit_hits = rate_limit_hits + 1
                WHERE connector_id = :c AND rate_limit_page = :p AND rate_limit_hits = 0
                """)
            .param("c", connectorId)
            .param("p", page)
            .update()
        == 1;
  }
}
