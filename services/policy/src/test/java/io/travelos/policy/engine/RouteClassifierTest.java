package io.travelos.policy.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class RouteClassifierTest {

  private final RouteClassifier classifier = RouteClassifier.fromClasspath();

  @Test
  void domesticWhenBothAirportsShareACountry() {
    RouteClassifier.Classification c = classifier.classify("BOS", "SEA");
    assertThat(c.international()).isFalse();
    assertThat(c.note()).isEqualTo("domestic: BOS(US)-SEA(US)");
  }

  @Test
  void internationalWhenAnyLegCrossesABorder() {
    assertThat(classifier.classify("BOS", "LHR").international()).isTrue();
    assertThat(
            classifier
                .classify(List.of(new String[] {"BOS", "JFK"}, new String[] {"JFK", "CDG"}))
                .international())
        .isTrue();
  }

  @Test
  void unknownAirportsFailClosedToInternational() {
    RouteClassifier.Classification c = classifier.classify("BOS", "XYZ");
    assertThat(c.international()).isTrue();
    assertThat(c.note()).contains("XYZ").contains("treated as international");
  }
}
