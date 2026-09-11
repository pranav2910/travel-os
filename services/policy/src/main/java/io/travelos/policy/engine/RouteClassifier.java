package io.travelos.policy.engine;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Domestic or international? Decided from a bundled IATA-to-country table. An unknown airport is
 * treated as international, which applies the stricter cabin list and says so in the note; the
 * proper reference-data service arrives with Slice 3 (international travel).
 */
public final class RouteClassifier {

  public record Classification(boolean international, String note) {}

  private final Map<String, String> countryByIata;

  public RouteClassifier(Map<String, String> countryByIata) {
    this.countryByIata = Map.copyOf(countryByIata);
  }

  public static RouteClassifier fromClasspath() {
    try (InputStream in = RouteClassifier.class.getResourceAsStream("/airports.csv")) {
      if (in == null) {
        throw new IllegalStateException("airports.csv missing from classpath");
      }
      Map<String, String> map = new HashMap<>();
      BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
      String line = reader.readLine(); // header
      while ((line = reader.readLine()) != null) {
        if (line.isBlank()) {
          continue;
        }
        String[] parts = line.split(",");
        map.put(parts[0].trim(), parts[1].trim());
      }
      return new RouteClassifier(map);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public Optional<String> country(String iata) {
    return Optional.ofNullable(countryByIata.get(iata));
  }

  public Classification classify(String origin, String destination) {
    return classify(List.<String[]>of(new String[] {origin, destination}));
  }

  /** Classifies a whole itinerary: international if any leg crosses a border or is unknown. */
  public Classification classify(List<String[]> legs) {
    StringBuilder note = new StringBuilder();
    boolean international = false;
    for (String[] leg : legs) {
      Optional<String> from = country(leg[0]);
      Optional<String> to = country(leg[1]);
      if (from.isEmpty() || to.isEmpty()) {
        String unknown = from.isEmpty() ? leg[0] : leg[1];
        return new Classification(
            true,
            "airport " + unknown + " is not in the reference table; treated as international");
      }
      if (!from.get().equals(to.get())) {
        international = true;
      }
      if (!note.isEmpty()) {
        note.append(", ");
      }
      note.append(leg[0]).append('(').append(from.get()).append(")-");
      note.append(leg[1]).append('(').append(to.get()).append(')');
    }
    return new Classification(
        international, (international ? "international: " : "domestic: ") + note);
  }
}
