package io.travelos.context.service;

import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/** Small text helpers shared with the rules (kept out of the rules' public surface). */
final class DetectionRulesText {
  private DetectionRulesText() {}

  static String quote(@Nullable String text) {
    if (text == null) {
      return "(untitled)";
    }
    String t = text.replace('\n', ' ').replace('\r', ' ').trim();
    if (t.length() > 80) {
      t = t.substring(0, 77) + "...";
    }
    return "'" + t + "'";
  }

  static String dates(@Nullable LocalDate start, @Nullable LocalDate end) {
    if (start == null) {
      return "(date to be confirmed)";
    }
    return start.equals(end) || end == null ? start.toString() : start + " to " + end;
  }
}
