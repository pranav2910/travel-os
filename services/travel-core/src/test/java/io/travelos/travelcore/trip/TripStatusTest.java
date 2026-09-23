package io.travelos.travelcore.trip;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TripStatusTest {

  @Test
  void happyPathIsReachable() {
    assertThat(TripStatus.SUBMITTED.canTransitionTo(TripStatus.PLANNING)).isTrue();
    assertThat(TripStatus.PLANNING.canTransitionTo(TripStatus.AWAITING_APPROVAL)).isTrue();
    assertThat(TripStatus.AWAITING_APPROVAL.canTransitionTo(TripStatus.APPROVED)).isTrue();
    assertThat(TripStatus.APPROVED.canTransitionTo(TripStatus.BOOKING)).isTrue();
    assertThat(TripStatus.BOOKING.canTransitionTo(TripStatus.BOOKED)).isTrue();
  }

  @Test
  void completionFollowsBookingOnlyAndIsTerminal() {
    assertThat(TripStatus.BOOKED.canTransitionTo(TripStatus.COMPLETED)).isTrue();
    assertThat(TripStatus.PLANNING.canTransitionTo(TripStatus.COMPLETED)).isFalse();
    assertThat(TripStatus.APPROVED.canTransitionTo(TripStatus.COMPLETED)).isFalse();
    for (TripStatus status : TripStatus.values()) {
      assertThat(TripStatus.COMPLETED.canTransitionTo(status)).isFalse();
    }
  }

  @Test
  void terminalStatesAreTerminal() {
    for (TripStatus status : TripStatus.values()) {
      assertThat(TripStatus.CANCELLED.canTransitionTo(status)).isFalse();
      assertThat(TripStatus.FAILED.canTransitionTo(status)).isFalse();
    }
    assertThat(TripStatus.CANCELLED.isTerminal()).isTrue();
    assertThat(TripStatus.BOOKED.isTerminal()).isFalse();
  }

  @Test
  void anApprovedTripWhoseQuoteExpiredIsPlannedAgainOrFailsHonestly() {
    assertThat(TripStatus.APPROVED.canTransitionTo(TripStatus.PLANNING))
        .as("a quote that died during a long approval means a fresh search, not a dead trip")
        .isTrue();
    assertThat(TripStatus.APPROVED.canTransitionTo(TripStatus.FAILED))
        .as("a supplier gone for good after approval is recorded, never left looking approved")
        .isTrue();
    assertThat(TripStatus.BOOKING.canTransitionTo(TripStatus.PLANNING)).isFalse();
  }

  @Test
  void aStaleApprovalGoesBackToAPersonOnlyFromApproved() {
    assertThat(TripStatus.APPROVED.canTransitionTo(TripStatus.AWAITING_APPROVAL))
        .as("revalidation before booking found a material change")
        .isTrue();
    assertThat(TripStatus.BOOKING.canTransitionTo(TripStatus.AWAITING_APPROVAL))
        .as("once a supplier mutation may have started there is no way back to approval")
        .isFalse();
    assertThat(TripStatus.BOOKED.canTransitionTo(TripStatus.AWAITING_APPROVAL)).isFalse();
  }

  @Test
  void aBookedTripIsReleasedAtTheSuppliersBeforeItIsCancelled() {
    assertThat(TripStatus.BOOKED.canTransitionTo(TripStatus.CANCELLED))
        .as("a confirmed reservation is never shown as cancelled before it is released")
        .isFalse();
    assertThat(TripStatus.BOOKED.canTransitionTo(TripStatus.CANCELLING)).isTrue();
    assertThat(TripStatus.CANCELLING.canTransitionTo(TripStatus.CANCELLED)).isTrue();
    for (TripStatus status : TripStatus.values()) {
      if (status != TripStatus.CANCELLED) {
        assertThat(TripStatus.CANCELLING.canTransitionTo(status))
            .as("CANCELLING only ever ends in CANCELLED, not " + status)
            .isFalse();
      }
      if (status != TripStatus.BOOKED) {
        assertThat(status.canTransitionTo(TripStatus.CANCELLING))
            .as("only a booked trip has something to release: " + status)
            .isFalse();
      }
    }
    assertThat(TripStatus.CANCELLING.isTerminal()).isFalse();
  }

  @Test
  void noShortcuts() {
    assertThat(TripStatus.SUBMITTED.canTransitionTo(TripStatus.BOOKED)).isFalse();
    assertThat(TripStatus.BOOKED.canTransitionTo(TripStatus.PLANNING)).isFalse();
    assertThat(TripStatus.BOOKING.canTransitionTo(TripStatus.CANCELLED))
        .as("a booking in flight is failed, then compensated — never just cancelled")
        .isFalse();
  }
}
