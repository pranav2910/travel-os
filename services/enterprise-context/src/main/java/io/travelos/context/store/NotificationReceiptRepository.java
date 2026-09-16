package io.travelos.context.store;

import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class NotificationReceiptRepository {
  private final JdbcClient jdbc;

  public NotificationReceiptRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** True when this provider event id was not seen before (and is now recorded). */
  public boolean record(
      String provider,
      String notificationId,
      String tenantId,
      @Nullable String connectorId,
      @Nullable String runId,
      Instant now) {
    return jdbc.sql(
                """
                INSERT INTO notification_receipt (provider, notification_id, tenant_id, connector_id, run_id, received_at)
                VALUES (:p, :n, :t, :c, :r, :now) ON CONFLICT (provider, notification_id) DO NOTHING
                """)
            .param("p", provider)
            .param("n", notificationId)
            .param("t", tenantId)
            .param("c", connectorId)
            .param("r", runId)
            .param("now", Rows.ts(now))
            .update()
        == 1;
  }

  public @Nullable String runOf(String provider, String notificationId) {
    return jdbc.sql(
            "SELECT run_id FROM notification_receipt WHERE provider = :p AND notification_id = :n")
        .param("p", provider)
        .param("n", notificationId)
        .query(String.class)
        .optional()
        .orElse(null);
  }
}
