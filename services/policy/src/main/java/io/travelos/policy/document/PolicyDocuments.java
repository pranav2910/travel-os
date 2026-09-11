package io.travelos.policy.document;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Parsing, validation and hashing of policy documents. Strict: an unknown field is an error. */
public final class PolicyDocuments {

  private static final JsonMapper MAPPER =
      JsonMapper.builder()
          .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
          .build();

  private PolicyDocuments() {}

  public static PolicyDocument parse(String json) {
    PolicyDocument document;
    try {
      document = MAPPER.readValue(json, PolicyDocument.class);
    } catch (JacksonException e) {
      throw new InvalidPolicyException(
          List.of("malformed policy document: " + e.getOriginalMessage()));
    }
    return validated(document);
  }

  public static PolicyDocument parse(JsonNode node) {
    PolicyDocument document;
    try {
      document = MAPPER.treeToValue(node, PolicyDocument.class);
    } catch (JacksonException e) {
      throw new InvalidPolicyException(
          List.of("malformed policy document: " + e.getOriginalMessage()));
    }
    return validated(document);
  }

  /** Deterministic JSON: record component order, no whitespace. The hash is computed over this. */
  public static String canonicalJson(PolicyDocument document) {
    return MAPPER.writeValueAsString(document);
  }

  public static String hash(PolicyDocument document) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of()
          .formatHex(digest.digest(canonicalJson(document).getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is mandatory in every JVM", e);
    }
  }

  public static JsonNode toJsonNode(PolicyDocument document) {
    return MAPPER.valueToTree(document);
  }

  private static PolicyDocument validated(PolicyDocument document) {
    List<String> problems = document.validate();
    if (!problems.isEmpty()) {
      throw new InvalidPolicyException(problems);
    }
    return document;
  }

  public static final class InvalidPolicyException extends RuntimeException {
    private final List<String> problems;

    public InvalidPolicyException(List<String> problems) {
      super("invalid policy document: " + String.join("; ", problems));
      this.problems = List.copyOf(problems);
    }

    public List<String> problems() {
      return problems;
    }
  }
}
