package io.travelos.context;

import static org.assertj.core.api.Assertions.assertThat;

import io.travelos.common.tenant.TenantId;
import io.travelos.context.model.Employee;
import io.travelos.context.store.EmployeeRepository;
import io.travelos.spring.web.testing.TestTokens;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Phase 7: SCIM 2.0 provisioning by an identity provider. A per-tenant token, the SCIM shapes Okta
 * and Entra ID send, and the platform's own rules underneath (a deactivation ends grants and puts
 * documents into retention; another tenant's token sees nothing; a user's JWT is not a provisioning
 * token).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestTokens.class)
@TestPropertySource(
    properties = {
      "travelos.context.integrations.scim.tokens.acme=test-scim-token-acme",
      "travelos.context.integrations.scim.tokens.globex=test-scim-token-globex"
    })
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ScimIntegrationTest {
  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11");

  @Container @ServiceConnection
  static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1");

  private static final String ACME = "test-scim-token-acme";
  private static final String GLOBEX = "test-scim-token-globex";
  private static final String OKTA_USER =
      """
      {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User","urn:ietf:params:scim:schemas:extension:enterprise:2.0:User"],
       "externalId":"00u1abcd", "userName":"grace.hopper@acme.example",
       "name":{"givenName":"Grace","familyName":"Hopper"}, "displayName":"Grace Hopper", "active":true,
       "emails":[{"value":"grace.hopper@acme.example","type":"work","primary":true}],
       "addresses":[{"type":"work","locality":"BOS","region":"MA","country":"US"}],
       "timezone":"America/New_York",
       "urn:ietf:params:scim:schemas:extension:enterprise:2.0:User":{"employeeNumber":"emp_1101","department":"dep_eng","costCenter":"cc_42","organization":"le_us","manager":{"value":"emp_1002"}}}
      """;

  @Autowired Environment environment;
  @Autowired EmployeeRepository employees;
  private final JsonMapper json = JsonMapper.builder().build();
  private RestClient http;

  @BeforeAll
  void setUp() {
    int port = environment.getRequiredProperty("local.server.port", Integer.class);
    http =
        RestClient.builder()
            .baseUrl("http://localhost:" + port)
            .defaultStatusHandler(s -> true, (rq, rs) -> {})
            .build();
  }

  @Test
  void anIdentityProviderProvisionsUpdatesAndDeactivatesUsersWithItsTenantsToken() {
    // no token, a wrong token, a person's JWT: none of them provisions
    assertThat(scim(HttpMethod.GET, "/scim/v2/Users", null, null).getStatusCode().value())
        .isEqualTo(401);
    assertThat(scim(HttpMethod.GET, "/scim/v2/Users", "nope", null).getStatusCode().value())
        .isEqualTo(401);
    assertThat(
            scim(HttpMethod.GET, "/scim/v2/Users", TestTokens.carol(), null)
                .getStatusCode()
                .value())
        .isEqualTo(401);
    JsonNode config =
        json.readTree(scim(HttpMethod.GET, "/scim/v2/ServiceProviderConfig", ACME, null).getBody());
    assertThat(config.get("patch").get("supported").asBoolean()).isTrue();

    // Okta creates a user
    ResponseEntity<String> created = scim(HttpMethod.POST, "/scim/v2/Users", ACME, OKTA_USER);
    assertThat(created.getStatusCode().value()).as(created.getBody()).isEqualTo(201);
    assertThat(created.getHeaders().getContentType()).hasToString("application/scim+json");
    JsonNode user = json.readTree(created.getBody());
    assertThat(user.get("id").asString()).isEqualTo("emp_1101");
    assertThat(user.get("userName").asString()).isEqualTo("grace.hopper@acme.example");
    assertThat(user.get("active").asBoolean()).isTrue();
    assertThat(
            user.get("urn:ietf:params:scim:schemas:extension:enterprise:2.0:User")
                .get("manager")
                .get("value")
                .asString())
        .isEqualTo("emp_1002");
    assertThat(
            user.get("urn:ietf:params:scim:schemas:extension:travelos:2.0:User")
                .get("workLocation")
                .asString())
        .isEqualTo("BOS");
    assertThat(user.get("meta").get("location").asString()).isEqualTo("/scim/v2/Users/emp_1101");
    Employee grace = employees.find(TenantId.of("acme"), "emp_1101").orElseThrow();
    assertThat(grace.email()).isEqualTo("grace.hopper@acme.example");
    assertThat(grace.managerEmployeeId()).isEqualTo("emp_1002");
    assertThat(grace.costCenterId()).isEqualTo("cc_42");
    assertThat(grace.timeZone().getId()).isEqualTo("America/New_York");
    assertThat(employees.findByExternalId(TenantId.of("acme"), "00u1abcd")).isPresent();
    // the same user again is a uniqueness conflict in SCIM's own shape
    ResponseEntity<String> again = scim(HttpMethod.POST, "/scim/v2/Users", ACME, OKTA_USER);
    assertThat(again.getStatusCode().value()).isEqualTo(409);
    assertThat(json.readTree(again.getBody()).get("scimType").asString()).isEqualTo("uniqueness");
    assertThat(json.readTree(again.getBody()).get("schemas").get(0).asString())
        .isEqualTo("urn:ietf:params:scim:api:messages:2.0:Error");

    // lookups the IdP performs
    JsonNode byName =
        json.readTree(
            scim(
                    HttpMethod.GET,
                    "/scim/v2/Users?filter=userName eq \"grace.hopper@acme.example\"",
                    ACME,
                    null)
                .getBody());
    assertThat(byName.get("totalResults").asInt()).isEqualTo(1);
    assertThat(byName.get("Resources").get(0).get("id").asString()).isEqualTo("emp_1101");
    assertThat(
            json.readTree(
                    scim(
                            HttpMethod.GET,
                            "/scim/v2/Users?filter=externalId eq \"00u1abcd\"",
                            ACME,
                            null)
                        .getBody())
                .get("totalResults")
                .asInt())
        .isEqualTo(1);
    assertThat(
            json.readTree(
                    scim(
                            HttpMethod.GET,
                            "/scim/v2/Users?filter=userName eq \"nobody@acme.example\"",
                            ACME,
                            null)
                        .getBody())
                .get("totalResults")
                .asInt())
        .isZero();
    assertThat(
            scim(HttpMethod.GET, "/scim/v2/Users?filter=title co \"x\"", ACME, null)
                .getStatusCode()
                .value())
        .isEqualTo(422);
    // another tenant's token sees nothing
    assertThat(
            scim(HttpMethod.GET, "/scim/v2/Users/emp_1101", GLOBEX, null).getStatusCode().value())
        .isEqualTo(404);
    assertThat(
            json.readTree(scim(HttpMethod.GET, "/scim/v2/Users", GLOBEX, null).getBody())
                .get("totalResults")
                .asInt())
        .isZero();

    // Entra ID patches: a new manager and a title, then deactivation
    String patch =
        """
        {"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
         "Operations":[{"op":"Replace","path":"urn:ietf:params:scim:schemas:extension:enterprise:2.0:User:manager","value":{"value":"emp_1003"}},
                       {"op":"replace","path":"displayName","value":"Grace B. Hopper"}]}
        """;
    ResponseEntity<String> patched = scim(HttpMethod.PATCH, "/scim/v2/Users/emp_1101", ACME, patch);
    assertThat(patched.getStatusCode().value()).as(patched.getBody()).isEqualTo(200);
    assertThat(json.readTree(patched.getBody()).get("displayName").asString())
        .isEqualTo("Grace B. Hopper");
    assertThat(employees.find(TenantId.of("acme"), "emp_1101").orElseThrow().managerEmployeeId())
        .isEqualTo("emp_1003");
    ResponseEntity<String> deactivated =
        scim(
            HttpMethod.PATCH,
            "/scim/v2/Users/emp_1101",
            ACME,
            "{\"schemas\":[\"urn:ietf:params:scim:api:messages:2.0:PatchOp\"],\"Operations\":[{\"op\":\"replace\",\"value\":{\"active\":false}}]}");
    assertThat(deactivated.getStatusCode().value()).as(deactivated.getBody()).isEqualTo(200);
    assertThat(json.readTree(deactivated.getBody()).get("active").asBoolean()).isFalse();
    assertThat(employees.find(TenantId.of("acme"), "emp_1101").orElseThrow().active()).isFalse();
    // a full replace reactivates and renames; a DELETE deactivates again (nothing is deleted)
    ResponseEntity<String> replaced =
        scim(
            HttpMethod.PUT,
            "/scim/v2/Users/emp_1101",
            ACME,
            OKTA_USER.replace("Grace Hopper", "Rear Admiral Hopper"));
    assertThat(replaced.getStatusCode().value()).as(replaced.getBody()).isEqualTo(200);
    assertThat(json.readTree(replaced.getBody()).get("displayName").asString())
        .isEqualTo("Rear Admiral Hopper");
    assertThat(json.readTree(replaced.getBody()).get("active").asBoolean()).isTrue();
    assertThat(
            scim(HttpMethod.DELETE, "/scim/v2/Users/emp_1101", ACME, null).getStatusCode().value())
        .isEqualTo(204);
    assertThat(employees.find(TenantId.of("acme"), "emp_1101").orElseThrow().active()).isFalse();
    assertThat(scim(HttpMethod.GET, "/scim/v2/Users/emp_1101", ACME, null).getStatusCode().value())
        .as("still readable, inactive")
        .isEqualTo(200);
    assertThat(
            scim(HttpMethod.DELETE, "/scim/v2/Users/emp_9999", ACME, null).getStatusCode().value())
        .isEqualTo(404);
    // a user without any usable identity is refused with the reason
    ResponseEntity<String> bad =
        scim(
            HttpMethod.POST,
            "/scim/v2/Users",
            ACME,
            "{\"schemas\":[\"urn:ietf:params:scim:schemas:core:2.0:User\"],\"userName\":\"x@acme.example\"}");
    assertThat(bad.getStatusCode().value()).isEqualTo(422);
    assertThat(json.readTree(bad.getBody()).get("detail").asString()).contains("employeeNumber");
  }

  private ResponseEntity<String> scim(HttpMethod method, String path, String token, String body) {
    RestClient.RequestBodySpec spec = http.method(method).uri(path);
    if (token != null) {
      spec = spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }
    if (body != null) {
      spec = spec.contentType(MediaType.parseMediaType("application/scim+json")).body(body);
    }
    return spec.retrieve().toEntity(String.class);
  }
}
