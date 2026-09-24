package io.travelos.assistance.store;

import java.time.Instant;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ProcessedEventRepository {
  private final JdbcClient jdbc;

  public ProcessedEventRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** True when this event id is new; false when it was processed before (a redelivery). */
  public boolean markProcessed(String eventId, String eventType, Instant now) {
    return jdbc.sql(
                """
                INSERT INTO processed_event (event_id, event_type, processed_at) VALUES (:id, :type, :now)
                ON CONFLICT (event_id) DO NOTHING
                """)
            .param("id", eventId)
            .param("type", eventType)
            .param("now", Rows.ts(now))
            .update()
        == 1;
  }
}
