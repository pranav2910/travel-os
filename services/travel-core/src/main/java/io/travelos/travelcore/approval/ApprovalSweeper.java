package io.travelos.travelcore.approval;

import io.travelos.travelcore.trip.TripService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Phase 7: unanswered approval steps escalate, then expire. */
@Component
public class ApprovalSweeper {
  private static final Logger log = LoggerFactory.getLogger(ApprovalSweeper.class);
  private final TripService trips;

  public ApprovalSweeper(TripService trips) {
    this.trips = trips;
  }

  @Scheduled(fixedDelayString = "${travelos.approvals.sweep:1m}")
  public void sweep() {
    int moved = trips.sweepExpiredApprovals();
    if (moved > 0) {
      log.warn("{} approval step(s) escalated or expired", moved);
    }
  }
}
