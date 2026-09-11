package io.travelos.travelcore.api;

import io.travelos.travelcore.approval.ApprovalSignaler;
import io.travelos.workflows.TripPlanning;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Stands in for Temporal in tests: remembers every signal the service would have sent. */
@TestConfiguration(proxyBeanMethods = false)
public class RecordingApprovalSignaler {

  public record Signal(String tripId, TripPlanning.ApprovalDecision decision) {}

  public static final List<Signal> SIGNALS = new CopyOnWriteArrayList<>();

  @Bean
  @Primary
  ApprovalSignaler recordingSignaler() {
    return (tripId, decision) -> SIGNALS.add(new Signal(tripId, decision));
  }
}
