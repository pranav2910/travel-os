package io.travelos.context.service;

/** Checkpoints are opaque strings from the source; numeric ones compare as numbers. */
final class Watermarks {
  private Watermarks() {}

  static int compare(String a, String b) {
    String x = a == null ? "" : a.trim();
    String y = b == null ? "" : b.trim();
    if (x.isEmpty() || y.isEmpty()) {
      return Boolean.compare(!x.isEmpty(), !y.isEmpty());
    }
    try {
      return Long.compare(Long.parseLong(x), Long.parseLong(y));
    } catch (NumberFormatException e) {
      return x.compareTo(y);
    }
  }
}
