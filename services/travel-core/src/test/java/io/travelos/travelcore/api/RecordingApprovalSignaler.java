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

  /** Phase 3 signals: purchaseAuthorized / selectionChanged / refreshQuote with their payload. */
  public record Other(String tripId, String signal, Object payload) {}

  public static final List<Signal> SIGNALS = new CopyOnWriteArrayList<>();
  public static final List<Other> OTHERS = new CopyOnWriteArrayList<>();

  @Bean
  @Primary
  ApprovalSignaler recordingSignaler() {
    return new ApprovalSignaler() {
      @Override
      public void approvalDecided(String tripId, TripPlanning.ApprovalDecision decision) {
        SIGNALS.add(new Signal(tripId, decision));
      }

      @Override
      public void purchaseAuthorized(String tripId, TripPlanning.PurchaseAuthorized authorized) {
        OTHERS.add(new Other(tripId, TripPlanning.SIGNAL_PURCHASE_AUTHORIZED, authorized));
      }

      @Override
      public void selectionChanged(String tripId, TripPlanning.SelectionChanged selection) {
        OTHERS.add(new Other(tripId, TripPlanning.SIGNAL_SELECTION_CHANGED, selection));
      }

      @Override
      public void refreshQuote(String tripId, String reason) {
        OTHERS.add(new Other(tripId, TripPlanning.SIGNAL_QUOTE_REFRESH, reason));
      }
    };
  }
}
