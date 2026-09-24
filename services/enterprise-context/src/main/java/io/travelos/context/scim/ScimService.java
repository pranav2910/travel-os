package io.travelos.context.scim;

import io.travelos.common.tenant.TenantId;
import io.travelos.context.model.Employee;
import io.travelos.context.service.ProfileService;
import io.travelos.context.store.EmployeeRepository;
import io.travelos.spring.web.auth.RequestPrincipal;
import io.travelos.spring.web.error.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Phase 7: SCIM 2.0 Users mapped onto employees. The IdP is authoritative for who exists and
 * whether they are active; the HRIS sync (when one runs) enriches the organizational fields but
 * cannot revive a user the IdP deactivated (deactivation goes through the same path as a travel
 * admin's, ADR-0014: grants end, documents enter retention).
 */
@Service
public class ScimService {
  private static final Logger log = LoggerFactory.getLogger(ScimService.class);
  static final String USER_SCHEMA = "urn:ietf:params:scim:schemas:core:2.0:User";
  static final String ENTERPRISE_SCHEMA =
      "urn:ietf:params:scim:schemas:extension:enterprise:2.0:User";
  static final String TRAVELOS_SCHEMA = "urn:ietf:params:scim:schemas:extension:travelos:2.0:User";
  static final String LIST_SCHEMA = "urn:ietf:params:scim:api:messages:2.0:ListResponse";
  static final String PATCH_SCHEMA = "urn:ietf:params:scim:api:messages:2.0:PatchOp";
  private static final Pattern FILTER =
      Pattern.compile(
          "^\\s*(userName|externalId|id)\\s+eq\\s+\"([^\"]+)\"\\s*$", Pattern.CASE_INSENSITIVE);
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final EmployeeRepository employees;
  private final ProfileService profiles;
  private final Clock clock;

  public ScimService(EmployeeRepository employees, ProfileService profiles, Clock clock) {
    this.employees = employees;
    this.profiles = profiles;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public Optional<Employee> get(TenantId tenant, String id) {
    return employees.find(tenant, id);
  }

  @Transactional(readOnly = true)
  public List<Employee> list(TenantId tenant, @Nullable String filter) {
    if (filter == null || filter.isBlank()) {
      return employees.list(tenant);
    }
    Matcher m = FILTER.matcher(filter);
    if (!m.matches()) {
      throw new ApiException.Unprocessable(
          "FILTER_UNSUPPORTED", "only userName eq, externalId eq and id eq filters are supported");
    }
    String attribute = m.group(1).toLowerCase(java.util.Locale.ROOT);
    String value = m.group(2);
    Optional<Employee> found =
        switch (attribute) {
          case "username" -> employees.findByEmail(tenant, value);
          case "externalid" -> employees.findByExternalId(tenant, value);
          default -> employees.find(tenant, value);
        };
    return found.map(List::of).orElse(List.of());
  }

  /** POST: creates, or answers 409 when the user name (email) or id is taken. */
  @Transactional
  public Employee create(RequestPrincipal me, JsonNode user) {
    TenantId tenant = me.tenant();
    Mapped m = map(tenant, user, null);
    if (employees.find(tenant, m.employee().employeeId()).isPresent()) {
      throw new ApiException.Conflict(
          "USER_EXISTS", "a user with id " + m.employee().employeeId() + " already exists");
    }
    Optional<Employee> byEmail = employees.findByEmail(tenant, m.employee().email());
    if (byEmail.isPresent() && !byEmail.get().employeeId().equals(m.employee().employeeId())) {
      throw new ApiException.Conflict(
          "USER_EXISTS",
          "userName " + m.employee().email() + " belongs to " + byEmail.get().employeeId());
    }
    employees.provision(m.employee(), m.externalId(), me.principal().id(), clock.instant());
    log.info(
        "scim: {} provisioned {} ({})",
        me.principal().id(),
        m.employee().employeeId(),
        m.employee().active() ? "active" : "inactive");
    return employees.find(tenant, m.employee().employeeId()).orElseThrow();
  }

  /** PUT: the whole resource replaced; the id stays. */
  @Transactional
  public Employee replace(RequestPrincipal me, String id, JsonNode user) {
    Employee existing =
        employees.find(me.tenant(), id).orElseThrow(() -> new ApiException.NotFound("User", id));
    Mapped m = map(me.tenant(), user, existing);
    boolean deactivating = existing.active() && !m.employee().active();
    if (deactivating) {
      // the platform's own offboarding first (grants end, documents enter retention), then the
      // record
      profiles.deactivate(me, id);
    }
    employees.provision(m.employee(), m.externalId(), me.principal().id(), clock.instant());
    return employees.find(me.tenant(), id).orElseThrow();
  }

  /** PATCH: replace/add operations on active, name, emails, title and the enterprise fields. */
  @Transactional
  public Employee patch(RequestPrincipal me, String id, JsonNode patch) {
    Employee existing =
        employees.find(me.tenant(), id).orElseThrow(() -> new ApiException.NotFound("User", id));
    ObjectNode current = toScim(existing, null);
    for (JsonNode op : patch.path("Operations")) {
      String kind = op.path("op").asString("replace").toLowerCase(java.util.Locale.ROOT);
      if (!kind.equals("replace") && !kind.equals("add") && !kind.equals("remove")) {
        throw new ApiException.Unprocessable("OP_UNSUPPORTED", "op must be add, replace or remove");
      }
      String path = op.path("path").asString("");
      JsonNode value = op.get("value");
      if (path.isBlank()) {
        if (value != null && value.isObject()) {
          value.properties().forEach(e -> apply(current, e.getKey(), e.getValue()));
        }
      } else {
        apply(current, path, kind.equals("remove") ? null : value);
      }
    }
    Mapped m = map(me.tenant(), current, existing);
    boolean deactivating = existing.active() && !m.employee().active();
    if (deactivating) {
      // the platform's own offboarding first (grants end, documents enter retention), then the
      // record
      profiles.deactivate(me, id);
    }
    employees.provision(m.employee(), m.externalId(), me.principal().id(), clock.instant());
    return employees.find(me.tenant(), id).orElseThrow();
  }

  /** DELETE: the IdP removed the user; the platform deactivates (nothing is deleted, ADR-0014). */
  @Transactional
  public void delete(RequestPrincipal me, String id) {
    Employee existing =
        employees.find(me.tenant(), id).orElseThrow(() -> new ApiException.NotFound("User", id));
    if (existing.active()) {
      profiles.deactivate(me, id);
    }
  }

  private static void apply(ObjectNode current, String path, @Nullable JsonNode value) {
    String p = path;
    ObjectNode target = current;
    if (p.startsWith(ENTERPRISE_SCHEMA + ":")) {
      target = (ObjectNode) current.withObject("/" + ENTERPRISE_SCHEMA.replace("/", "~1"));
      p = p.substring(ENTERPRISE_SCHEMA.length() + 1);
    } else if (p.startsWith(TRAVELOS_SCHEMA + ":")) {
      target = (ObjectNode) current.withObject("/" + TRAVELOS_SCHEMA.replace("/", "~1"));
      p = p.substring(TRAVELOS_SCHEMA.length() + 1);
    }
    if (p.startsWith("emails[")) {
      ArrayNode emails = current.putArray("emails");
      if (value != null) {
        emails.addObject().put("value", value.asString()).put("primary", true);
      }
      return;
    }
    String[] parts = p.split("\\.");
    ObjectNode node = target;
    for (int i = 0; i < parts.length - 1; i++) {
      node = node.withObject("/" + parts[i]);
    }
    if (value == null) {
      node.remove(parts[parts.length - 1]);
    } else {
      node.set(parts[parts.length - 1], value);
    }
  }

  record Mapped(Employee employee, @Nullable String externalId) {}

  private Mapped map(TenantId tenant, JsonNode user, @Nullable Employee existing) {
    JsonNode enterprise = user.path(ENTERPRISE_SCHEMA);
    JsonNode travelos = user.path(TRAVELOS_SCHEMA);
    String externalId = text(user, "externalId");
    String employeeId =
        first(
            text(enterprise, "employeeNumber"),
            existing == null ? null : existing.employeeId(),
            externalId,
            null);
    String email =
        first(
            text(user, "userName"),
            primaryEmail(user),
            existing == null ? null : existing.email(),
            null);
    if (employeeId == null || employeeId.isBlank()) {
      throw new ApiException.Unprocessable(
          "ID_REQUIRED", "employeeNumber (enterprise extension) or externalId is required");
    }
    if (email == null || email.isBlank() || !email.contains("@")) {
      throw new ApiException.Unprocessable(
          "USERNAME_REQUIRED", "userName must be the work email address");
    }
    String displayName =
        first(
            text(user, "displayName"),
            join(text(user.path("name"), "givenName"), text(user.path("name"), "familyName")),
            existing == null ? null : existing.displayName(),
            email);
    String workLocation =
        first(
            text(travelos, "workLocation"),
            workAddressCode(user),
            existing == null ? null : existing.workLocation(),
            "UNK");
    String timeZone =
        first(
            text(travelos, "timeZone"),
            text(user, "timezone"),
            existing == null ? null : existing.timeZone().getId(),
            "UTC");
    ZoneId zone;
    try {
      zone = ZoneId.of(timeZone);
    } catch (RuntimeException e) {
      zone = ZoneId.of("UTC");
    }
    boolean active =
        user.path("active").isMissingNode()
            ? existing == null || existing.active()
            : user.path("active").asBoolean(true);
    String manager =
        first(
            text(enterprise.path("manager"), "value"),
            existing == null ? null : existing.managerEmployeeId(),
            null,
            null);
    Instant now = clock.instant();
    Employee e =
        new Employee(
            tenant,
            employeeId,
            email,
            displayName,
            workLocation.length() > 3
                ? workLocation.substring(0, 3).toUpperCase(java.util.Locale.ROOT)
                : workLocation.toUpperCase(java.util.Locale.ROOT),
            zone,
            manager == null || manager.isBlank() ? null : manager,
            active,
            now.toEpochMilli(),
            now,
            first(
                text(enterprise, "department"),
                existing == null ? null : existing.departmentId(),
                null,
                null),
            first(
                text(enterprise, "costCenter"),
                existing == null ? null : existing.costCenterId(),
                null,
                null),
            first(
                text(enterprise, "organization"),
                existing == null ? null : existing.legalEntityId(),
                null,
                null),
            first(
                text(travelos, "officeId"),
                existing == null ? null : existing.officeId(),
                null,
                null));
    return new Mapped(e, externalId);
  }

  /** The SCIM representation of an employee. */
  public ObjectNode toScim(Employee e, @Nullable String location) {
    ObjectNode u = JSON.createObjectNode();
    ArrayNode schemas = u.putArray("schemas");
    schemas.add(USER_SCHEMA).add(ENTERPRISE_SCHEMA).add(TRAVELOS_SCHEMA);
    u.put("id", e.employeeId());
    u.put("userName", e.email());
    u.put("displayName", e.displayName());
    u.put("active", e.active());
    u.put("timezone", e.timeZone().getId());
    ArrayNode emails = u.putArray("emails");
    emails.addObject().put("value", e.email()).put("type", "work").put("primary", true);
    ObjectNode enterprise = u.putObject(ENTERPRISE_SCHEMA);
    enterprise.put("employeeNumber", e.employeeId());
    if (e.departmentId() != null) {
      enterprise.put("department", e.departmentId());
    }
    if (e.costCenterId() != null) {
      enterprise.put("costCenter", e.costCenterId());
    }
    if (e.legalEntityId() != null) {
      enterprise.put("organization", e.legalEntityId());
    }
    if (e.managerEmployeeId() != null) {
      enterprise.putObject("manager").put("value", e.managerEmployeeId());
    }
    ObjectNode travelos = u.putObject(TRAVELOS_SCHEMA);
    travelos.put("workLocation", e.workLocation());
    travelos.put("timeZone", e.timeZone().getId());
    if (e.officeId() != null) {
      travelos.put("officeId", e.officeId());
    }
    ObjectNode meta = u.putObject("meta");
    meta.put("resourceType", "User");
    meta.put("lastModified", e.updatedAt().toString());
    meta.put("version", "W/\"" + e.sourceRevision() + "\"");
    if (location != null) {
      meta.put("location", location);
    }
    return u;
  }

  public ObjectNode listResponse(List<Employee> users, int startIndex, String locationPrefix) {
    ObjectNode r = JSON.createObjectNode();
    r.putArray("schemas").add(LIST_SCHEMA);
    r.put("totalResults", users.size());
    r.put("startIndex", startIndex);
    r.put("itemsPerPage", users.size());
    ArrayNode resources = r.putArray("Resources");
    users.forEach(u -> resources.add(toScim(u, locationPrefix + "/" + u.employeeId())));
    return r;
  }

  public static ObjectNode error(int status, String detail, @Nullable String scimType) {
    ObjectNode e = JSON.createObjectNode();
    e.putArray("schemas").add("urn:ietf:params:scim:api:messages:2.0:Error");
    e.put("status", String.valueOf(status));
    e.put("detail", detail);
    if (scimType != null) {
      e.put("scimType", scimType);
    }
    return e;
  }

  static Map<String, Object> serviceProviderConfig() {
    Map<String, Object> c = new LinkedHashMap<>();
    c.put("schemas", List.of("urn:ietf:params:scim:schemas:core:2.0:ServiceProviderConfig"));
    c.put("patch", Map.of("supported", true));
    c.put("bulk", Map.of("supported", false, "maxOperations", 0, "maxPayloadSize", 0));
    c.put("filter", Map.of("supported", true, "maxResults", 200));
    c.put("changePassword", Map.of("supported", false));
    c.put("sort", Map.of("supported", false));
    c.put("etag", Map.of("supported", false));
    c.put(
        "authenticationSchemes",
        List.of(
            Map.of(
                "type",
                "oauthbearertoken",
                "name",
                "Bearer token",
                "description",
                "A per-tenant provisioning token issued by the platform's secrets mechanism")));
    return c;
  }

  private static @Nullable String primaryEmail(JsonNode user) {
    JsonNode emails = user.path("emails");
    String any = null;
    for (JsonNode e : emails) {
      String v = text(e, "value");
      if (v == null) {
        continue;
      }
      if (e.path("primary").asBoolean(false)) {
        return v;
      }
      any = any == null ? v : any;
    }
    return any;
  }

  private static @Nullable String workAddressCode(JsonNode user) {
    for (JsonNode a : user.path("addresses")) {
      String locality = text(a, "locality");
      if (locality != null && locality.matches("^[A-Za-z]{3}$")) {
        return locality.toUpperCase(java.util.Locale.ROOT);
      }
    }
    return null;
  }

  private static @Nullable String text(JsonNode node, String field) {
    JsonNode v = node.get(field);
    return v == null || v.isNull() || v.asString().isBlank() ? null : v.asString();
  }

  private static @Nullable String join(@Nullable String a, @Nullable String b) {
    List<String> parts = new ArrayList<>();
    if (a != null) {
      parts.add(a);
    }
    if (b != null) {
      parts.add(b);
    }
    return parts.isEmpty() ? null : String.join(" ", parts);
  }

  @SafeVarargs
  private static <T> @Nullable T first(T... candidates) {
    for (T c : candidates) {
      if (c != null && !(c instanceof String s && s.isBlank())) {
        return c;
      }
    }
    return null;
  }
}
