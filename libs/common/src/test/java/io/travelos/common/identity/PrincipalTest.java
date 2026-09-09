package io.travelos.common.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PrincipalTest {

  @Test
  void parsesAllThreeKinds() {
    assertThat(Principal.parse("human/alice")).isEqualTo(new Principal.Human("alice"));
    assertThat(Principal.parse("service/order-service"))
        .isEqualTo(new Principal.Service("order-service"));
    assertThat(Principal.parse("agent/disruption-recovery/v1"))
        .isEqualTo(new Principal.Agent("disruption-recovery", "v1"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"human/alice", "service/order-service", "agent/disruption-recovery/v12"})
  void idRoundTrips(String id) {
    assertThat(Principal.parse(id).id()).isEqualTo(id);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "alice",
        "root/alice",
        "human/",
        "human/Alice",
        "human/alice/extra",
        "agent/planner",
        "agent/planner/1",
        "agent/planner/v1/extra"
      })
  void rejectsMalformedIds(String id) {
    assertThatThrownBy(() -> Principal.parse(id)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void agentsAreVersioned() {
    assertThatThrownBy(() -> new Principal.Agent("planner", "latest"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
