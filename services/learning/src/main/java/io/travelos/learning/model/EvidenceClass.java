package io.travelos.learning.model;

/** Where evidence comes from. SANDBOX providers are SIMULATED; their evidence never trains LIVE. */
public enum EvidenceClass {
  SANDBOX,
  LIVE;

  /** The sandbox suppliers all announce themselves with a {@code sandbox-} provider name. */
  public static EvidenceClass ofProvider(String provider) {
    return provider != null && provider.startsWith("sandbox-") ? SANDBOX : LIVE;
  }
}
