package io.travelos.context.source.sandbox;

import io.travelos.context.ContextProperties;
import io.travelos.context.model.ConnectorKind;
import io.travelos.context.source.CalendarEvent;
import io.travelos.context.source.CalendarSource;
import io.travelos.context.store.SandboxStore;
import org.springframework.stereotype.Component;

/** SIMULATED calendar system. */
@Component
public class SandboxCalendarSource extends SandboxSource<CalendarEvent> implements CalendarSource {
  public SandboxCalendarSource(SandboxStore store, ContextProperties properties) {
    super(store, properties, CalendarEvent.class);
  }

  @Override
  public ConnectorKind kind() {
    return ConnectorKind.CALENDAR;
  }
}
