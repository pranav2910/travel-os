package io.travelos.context.source.sandbox;

import io.travelos.context.ContextProperties;
import io.travelos.context.model.Connector;
import io.travelos.context.model.ConnectorKind;
import io.travelos.context.source.EnterpriseSource;
import io.travelos.context.source.SourceException;
import io.travelos.context.source.SourcePage;
import io.travelos.context.store.SandboxStore;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.json.JsonMapper;

/**
 * The SIMULATED enterprise system: a table of items with a sequence, served in pages after a
 * watermark, with two faults on demand (an outage that fails the next N calls, a rate limit that
 * refuses one page once). Deterministic: the same store and the same faults give the same pages.
 *
 * <p>Cursor: {@code p:<page index>}; checkpoint (since): the last sequence stored; watermark: the
 * last sequence of the page. A retried page returns exactly the same entries.
 */
abstract class SandboxSource<T> implements EnterpriseSource<T> {
  static final JsonMapper JSON = JsonMapper.builder().build();
  private final SandboxStore store;
  private final ContextProperties properties;
  private final Class<T> type;

  SandboxSource(SandboxStore store, ContextProperties properties, Class<T> type) {
    this.store = store;
    this.properties = properties;
    this.type = type;
  }

  @Override
  public String provider() {
    return "sandbox-" + kind().name().toLowerCase(java.util.Locale.ROOT);
  }

  @Override
  public boolean simulated() {
    return true;
  }

  @Override
  public SourcePage<T> fetch(Connector connector, String since, String cursor) {
    int page =
        cursor == null || cursor.isBlank() ? 0 : Integer.parseInt(cursor.substring("p:".length()));
    if (store.consumeUnavailable(connector.connectorId())) {
      throw new SourceException(
          "SOURCE_UNAVAILABLE", provider() + " is simulating an outage; retry later", true);
    }
    if (store.consumeRateLimit(connector.connectorId(), page)) {
      throw new SourceException(
          "RATE_LIMITED", provider() + " rate limit reached on page " + page + "; back off", true);
    }
    long after = since == null || since.isBlank() ? 0L : Long.parseLong(since);
    int size = properties.connectors().sandboxPageSize();
    List<SandboxStore.Item> items = store.page(connector.connectorId(), after, page * size, size);
    List<SourcePage.Entry<T>> entries = new ArrayList<>();
    long watermark = after;
    for (SandboxStore.Item it : items) {
      entries.add(
          new SourcePage.Entry<>(
              it.sourceId(),
              it.revision(),
              it.deleted(),
              it.deleted() ? null : JSON.readValue(it.payloadJson(), type)));
      watermark = Math.max(watermark, it.seq());
    }
    boolean done = items.size() < size;
    return new SourcePage<>(entries, "p:" + (page + 1), done, Long.toString(watermark));
  }

  static ConnectorKind of(String name) {
    return ConnectorKind.valueOf(name);
  }
}
