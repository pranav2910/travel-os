package io.travelos.common.ids;

import com.github.f4b6a3.ulid.UlidCreator;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Prefixed, time-sortable identifiers. ULIDs are 26 chars of Crockford base32 and sort by creation
 * time, which keeps B-tree inserts append-mostly and makes "newest first" a plain ORDER BY.
 */
public final class Ids {

  private static final Pattern ULID = Pattern.compile("[0-9A-HJKMNP-TV-Z]{26}");

  private Ids() {}

  /** Monotonic within a process: two ids generated in the same millisecond still sort correctly. */
  public static String newId(IdPrefix prefix) {
    return prefix.prefix() + "_" + UlidCreator.getMonotonicUlid();
  }

  public static boolean isValid(IdPrefix prefix, String id) {
    String expected = prefix.prefix() + "_";
    return id != null
        && id.length() == expected.length() + 26
        && id.startsWith(expected)
        && ULID.matcher(id.substring(expected.length())).matches();
  }

  public static Optional<IdPrefix> prefixOf(String id) {
    if (id == null) {
      return Optional.empty();
    }
    int underscore = id.indexOf('_');
    if (underscore <= 0) {
      return Optional.empty();
    }
    String prefix = id.substring(0, underscore);
    for (IdPrefix candidate : IdPrefix.values()) {
      if (candidate.prefix().equals(prefix) && isValid(candidate, id)) {
        return Optional.of(candidate);
      }
    }
    return Optional.empty();
  }

  /** Throws with a precise message; use at service boundaries so bad ids fail fast and loudly. */
  public static String require(IdPrefix prefix, String id) {
    if (!isValid(prefix, id)) {
      throw new IllegalArgumentException(
          "expected a " + prefix.prefix() + "_<ULID> id but got: " + id);
    }
    return id;
  }
}
