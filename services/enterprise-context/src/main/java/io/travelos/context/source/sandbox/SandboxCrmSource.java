package io.travelos.context.source.sandbox;

import io.travelos.context.ContextProperties;
import io.travelos.context.model.ConnectorKind;
import io.travelos.context.source.CrmRecord;
import io.travelos.context.source.CrmSource;
import io.travelos.context.store.SandboxStore;
import org.springframework.stereotype.Component;

/** SIMULATED crm system. */
@Component
public class SandboxCrmSource extends SandboxSource<CrmRecord> implements CrmSource {
  public SandboxCrmSource(SandboxStore store, ContextProperties properties) {
    super(store, properties, CrmRecord.class);
  }

  @Override
  public ConnectorKind kind() {
    return ConnectorKind.CRM;
  }
}
