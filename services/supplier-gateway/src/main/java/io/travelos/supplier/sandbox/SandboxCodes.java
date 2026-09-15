package io.travelos.supplier.sandbox;

/** Six-character confirmation codes the way suppliers print them: deterministic from a seed. */
final class SandboxCodes {
  private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

  private SandboxCodes() {}

  static String confirmation(String seed) {
    long h = 1125899906842597L;
    for (int i = 0; i < seed.length(); i++) {
      h = 31 * h + seed.charAt(i);
    }
    StringBuilder out = new StringBuilder(6);
    for (int i = 0; i < 6; i++) {
      out.append(ALPHABET.charAt(Math.floorMod(h >> (5 * i), ALPHABET.length())));
    }
    return out.toString();
  }
}
