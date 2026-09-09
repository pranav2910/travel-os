package io.travelos.common.identity;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Who is acting. Three kinds, three identities: {@code human/alice}, {@code service/order-service},
 * {@code agent/disruption-recovery/v1}. Agents are machine identities with scoped capabilities — an
 * LLM prompt is never a principal, the agent that runs it is.
 */
public sealed interface Principal permits Principal.Human, Principal.Service, Principal.Agent {

  Pattern NAME = Pattern.compile("^[a-z0-9][a-z0-9-]*$");
  Pattern VERSION = Pattern.compile("^v[0-9]+$");

  /** Canonical string form, stable across serialization boundaries. */
  String id();

  static Principal parse(String id) {
    Objects.requireNonNull(id, "principal id");
    String[] parts = id.split("/", -1);
    return switch (parts[0]) {
      case "human" -> requireParts(parts, 2, id) ? new Human(parts[1]) : fail(id);
      case "service" -> requireParts(parts, 2, id) ? new Service(parts[1]) : fail(id);
      case "agent" -> requireParts(parts, 3, id) ? new Agent(parts[1], parts[2]) : fail(id);
      default -> fail(id);
    };
  }

  private static boolean requireParts(String[] parts, int expected, String id) {
    return parts.length == expected;
  }

  private static Principal fail(String id) {
    throw new IllegalArgumentException(
        "principal must be human/<name>, service/<name> or agent/<name>/v<n> but got: " + id);
  }

  private static String requireName(String name) {
    if (name == null || !NAME.matcher(name).matches()) {
      throw new IllegalArgumentException("principal name must match [a-z0-9-]: " + name);
    }
    return name;
  }

  record Human(String subject) implements Principal {
    public Human {
      requireName(subject);
    }

    @Override
    public String id() {
      return "human/" + subject;
    }
  }

  record Service(String name) implements Principal {
    public Service {
      requireName(name);
    }

    @Override
    public String id() {
      return "service/" + name;
    }
  }

  record Agent(String name, String version) implements Principal {
    public Agent {
      requireName(name);
      if (version == null || !VERSION.matcher(version).matches()) {
        throw new IllegalArgumentException("agent version must look like v1: " + version);
      }
    }

    @Override
    public String id() {
      return "agent/" + name + "/" + version;
    }
  }
}
