package io.travelos.context.model;

import java.util.LinkedHashMap;
import java.util.Map;

/** A contributing source, kept on the candidate forever: evidence is added to, never replaced. */
public record SourceRef(
    String connectorId, ConnectorKind kind, String sourceId, long revision, Role role) {
  public enum Role {
    /** The commitment the candidate was created from. */
    PRIMARY,
    /** Another record of the same visit (a CRM visit for the calendar event). */
    CORRELATED,
    /** Context that does not create demand (a past expense in the same city). */
    ENRICHMENT,
    /** Spend or travel that may mean this trip already exists. */
    DUPLICATE_SIGNAL
  }

  public Map<String, Object> toMap() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("connectorId", connectorId);
    m.put("kind", kind.name());
    m.put("sourceId", sourceId);
    m.put("revision", revision);
    m.put("role", role.name());
    return m;
  }

  public String key() {
    return connectorId + "|" + sourceId;
  }
}
