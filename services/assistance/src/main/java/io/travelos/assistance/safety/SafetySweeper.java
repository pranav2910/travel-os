package io.travelos.assistance.safety;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Phase 8: travelers who did not check in on a serious advisory become cases when the grace ends.
 */
@Component
public class SafetySweeper {
  private static final Logger log = LoggerFactory.getLogger(SafetySweeper.class);
  private final SafetyService safety;

  public SafetySweeper(SafetyService safety) {
    this.safety = safety;
  }

  @Scheduled(fixedDelayString = "${travelos.assistance.escalation-sweep:1m}")
  public void sweep() {
    int opened = safety.openCasesForSilentTravelers();
    if (opened > 0) {
      log.warn("{} safety case(s) opened for travelers who did not check in", opened);
    }
  }
}
