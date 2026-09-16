package io.travelos.learning.store;

import io.travelos.common.tenant.TenantId;
import io.travelos.learning.model.Feedback;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class FeedbackRepository {
  private final JdbcClient jdbc;

  public FeedbackRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public void insert(Feedback f) {
    jdbc.sql(
            """
            INSERT INTO feedback (feedback_id, tenant_id, trip_id, traveler_id, component_id, supplier_key, provider,
              revision, rating, tags, comment, recorded_by, recorded_at)
            VALUES (:id, :t, :trip, :traveler, :component, :key, :provider, :rev, :rating, CAST(:tags AS jsonb), :comment, :by, :at)
            """)
        .param("id", f.feedbackId())
        .param("t", f.tenant().value())
        .param("trip", f.tripId())
        .param("traveler", f.travelerId())
        .param("component", f.componentId())
        .param("key", f.supplierKey())
        .param("provider", f.provider())
        .param("rev", f.revision())
        .param("rating", f.rating())
        .param("tags", Rows.json(f.tags()))
        .param("comment", f.comment())
        .param("by", f.recordedBy())
        .param("at", Rows.ts(f.recordedAt()))
        .update();
  }

  /** The latest revision for (trip, traveler, component). */
  public Optional<Feedback> current(
      TenantId tenant, String tripId, String travelerId, String componentId) {
    return jdbc.sql(
            """
            SELECT * FROM feedback WHERE tenant_id = :t AND trip_id = :trip AND traveler_id = :traveler
              AND component_id = :component ORDER BY revision DESC LIMIT 1
            """)
        .param("t", tenant.value())
        .param("trip", tripId)
        .param("traveler", travelerId)
        .param("component", componentId)
        .query(FeedbackRepository::map)
        .optional();
  }

  public List<Feedback> byTrip(TenantId tenant, String tripId) {
    return jdbc.sql(
            "SELECT * FROM feedback WHERE tenant_id = :t AND trip_id = :trip ORDER BY component_id, revision")
        .param("t", tenant.value())
        .param("trip", tripId)
        .query(FeedbackRepository::map)
        .list();
  }

  static Feedback map(ResultSet rs, int i) throws SQLException {
    return new Feedback(
        rs.getString("feedback_id"),
        TenantId.of(rs.getString("tenant_id")),
        rs.getString("trip_id"),
        rs.getString("traveler_id"),
        rs.getString("component_id"),
        rs.getString("supplier_key"),
        rs.getString("provider"),
        rs.getInt("revision"),
        rs.getInt("rating"),
        Rows.strings(rs, "tags"),
        rs.getString("comment"),
        rs.getString("recorded_by"),
        Rows.instantOrThrow(rs, "recorded_at"));
  }
}
