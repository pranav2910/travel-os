package io.travelos.context.source.sandbox;

import io.travelos.context.ContextProperties;
import io.travelos.context.model.ConnectorKind;
import io.travelos.context.source.HrisRecord;
import io.travelos.context.source.HrisSource;
import io.travelos.context.store.SandboxStore;
import org.springframework.stereotype.Component;

/** SIMULATED hris system. */
@Component
public class SandboxHrisSource extends SandboxSource<HrisRecord> implements HrisSource {
  public SandboxHrisSource(SandboxStore store, ContextProperties properties) {
    super(store, properties, HrisRecord.class);
  }

  @Override
  public ConnectorKind kind() {
    return ConnectorKind.HRIS;
  }
}
