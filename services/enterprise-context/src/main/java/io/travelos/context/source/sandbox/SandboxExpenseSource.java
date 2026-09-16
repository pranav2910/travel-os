package io.travelos.context.source.sandbox;

import io.travelos.context.ContextProperties;
import io.travelos.context.model.ConnectorKind;
import io.travelos.context.source.ExpenseRecord;
import io.travelos.context.source.ExpenseSource;
import io.travelos.context.store.SandboxStore;
import org.springframework.stereotype.Component;

/** SIMULATED expense system. */
@Component
public class SandboxExpenseSource extends SandboxSource<ExpenseRecord> implements ExpenseSource {
  public SandboxExpenseSource(SandboxStore store, ContextProperties properties) {
    super(store, properties, ExpenseRecord.class);
  }

  @Override
  public ConnectorKind kind() {
    return ConnectorKind.EXPENSE;
  }
}
