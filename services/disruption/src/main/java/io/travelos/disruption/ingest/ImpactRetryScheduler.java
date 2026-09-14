package io.travelos.disruption.ingest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** DETECTED rows whose impact could not be confirmed (Order service down) get another try. */
@Component
public class ImpactRetryScheduler {

  private static final Logger log = LoggerFactory.getLogger(ImpactRetryScheduler.class);
  private final DisruptionIngestService ingest;

  public ImpactRetryScheduler(DisruptionIngestService ingest) {
    this.ingest = ingest;
  }

  @Scheduled(fixedDelayString = "${travelos.disruption.impact-retry-interval:5s}")
  public void retry() {
    int n = ingest.confirmPending(50);
    if (n > 0) {
      log.info("impact confirmed for {} deferred disruption(s)", n);
    }
  }
}
