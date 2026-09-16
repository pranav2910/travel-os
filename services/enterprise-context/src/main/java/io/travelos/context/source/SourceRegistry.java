package io.travelos.context.source;

import io.travelos.context.model.ConnectorKind;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Every source the service can talk to, by (kind, provider). */
@Component
public class SourceRegistry {
  private final Map<String, EnterpriseSource<?>> sources;

  public SourceRegistry(List<EnterpriseSource<?>> sources) {
    this.sources =
        sources.stream()
            .collect(Collectors.toMap(s -> key(s.kind(), s.provider()), Function.identity()));
  }

  public Optional<EnterpriseSource<?>> find(ConnectorKind kind, String provider) {
    return Optional.ofNullable(sources.get(key(kind, provider)));
  }

  public List<String> providers() {
    return sources.keySet().stream().sorted().toList();
  }

  private static String key(ConnectorKind kind, String provider) {
    return kind.name() + "/" + provider;
  }
}
