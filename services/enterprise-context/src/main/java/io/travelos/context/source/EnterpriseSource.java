package io.travelos.context.source;

import io.travelos.context.model.Connector;
import io.travelos.context.model.ConnectorKind;

/**
 * The port every enterprise integration implements. Live providers (Google Workspace, Microsoft
 * 365, Salesforce, Workday, SAP Concur, ...) plug in here with their own credentials and paging;
 * the platform never sees a vendor shape above this interface. Sandbox implementations are
 * SIMULATED and report themselves as such.
 */
public interface EnterpriseSource<T> {
  ConnectorKind kind();

  String provider();

  /** True for the deterministic sandbox, false for a live provider. */
  boolean simulated();

  /**
   * @param since the connector's durable checkpoint (empty = everything)
   * @param cursor the position inside this run (empty = first page)
   * @throws SourceException when the source cannot answer now
   */
  SourcePage<T> fetch(Connector connector, String since, String cursor);
}
