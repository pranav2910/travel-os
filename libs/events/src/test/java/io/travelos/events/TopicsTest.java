package io.travelos.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class TopicsTest {

  @Test
  void topicIsTheFirstTwoSegments() {
    assertThat(Topics.topicFor("travel.order.confirmed")).isEqualTo(Topics.ORDER);
    assertThat(Topics.topicFor("travel.agent.action.requested")).isEqualTo(Topics.AGENT);
  }

  @Test
  void unregisteredTopicsAreRejected() {
    assertThatThrownBy(() -> Topics.topicFor("travel.payments.captured"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unregistered");
    assertThatThrownBy(() -> Topics.topicFor("travel.order"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
