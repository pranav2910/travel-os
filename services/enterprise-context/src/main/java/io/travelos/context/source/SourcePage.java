package io.travelos.context.source;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One page of a source's changes since a watermark. Entries carry the source's own id and revision
 * so the platform can tell a redelivery from a change and an old revision from a new one.
 *
 * @param watermark the durable checkpoint a later run may start from once this page is stored
 */
public record SourcePage<T>(
    List<Entry<T>> entries, String nextCursor, boolean done, String watermark) {
  public record Entry<T>(String sourceId, long revision, boolean deleted, @Nullable T item) {}
}
