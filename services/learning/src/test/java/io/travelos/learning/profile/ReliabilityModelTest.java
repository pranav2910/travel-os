package io.travelos.learning.profile;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ReliabilityModelTest {
  @Test
  void noObservationsIsThePriorNotUnreliable() {
    assertThat(ReliabilityModel.estimate(0, 0, 8, 2)).isEqualTo(0.8);
    assertThat(ReliabilityModel.adjustment(0.8, 0.8, 40, 10)).isEqualTo(0.0);
  }

  @Test
  void adjustmentIsBoundedAndFinite() {
    assertThat(ReliabilityModel.adjustment(0.0, 0.8, 40, 10)).isEqualTo(-10.0);
    assertThat(ReliabilityModel.adjustment(1.0, 0.8, 40, 10)).isEqualTo(8.0);
    assertThat(ReliabilityModel.adjustment(Double.NaN, 0.8, 40, 10)).isEqualTo(0.0);
    assertThat(ReliabilityModel.adjustment(Double.POSITIVE_INFINITY, 0.8, 40, 10)).isEqualTo(0.0);
  }

  @Test
  void smoothingPullsSmallSamplesTowardThePrior() {
    // three failures out of three: the posterior mean is 8/13, not 0
    assertThat(ReliabilityModel.estimate(0, 3, 8, 2))
        .isCloseTo(0.615, org.assertj.core.data.Offset.offset(0.001));
    assertThat(ReliabilityModel.adjustment(0.615, 0.8, 40, 10))
        .isCloseTo(-7.4, org.assertj.core.data.Offset.offset(0.01));
  }

  @Test
  void preferenceIsShrunkAndBounded() {
    assertThat(ReliabilityModel.preference(List.of(), 5)).isEqualTo(0.0);
    assertThat(ReliabilityModel.preference(List.of(5), 5)).isEqualTo(2.5);
    assertThat(ReliabilityModel.preference(List.of(1, 1, 1, 1, 1, 1, 1, 1, 1), 5)).isEqualTo(-4.5);
    assertThat(ReliabilityModel.preference(List.of(3), 5)).isEqualTo(0.0);
  }
}
