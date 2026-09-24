package io.travelos.context;

import java.time.Duration;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * travelos.context.integrations.*: Phase 7 credentials for provisioning and the genuine enterprise
 * adapters. Every secret comes from the environment (the platform's secrets mechanism); an absent
 * credential means the adapter does not exist. Nothing here is logged.
 */
@ConfigurationProperties(prefix = "travelos.context.integrations")
public record IntegrationProperties(
    @Nullable Scim scim,
    @Nullable Workday workday,
    @Nullable Google google,
    @Nullable Microsoft microsoft,
    @Nullable Salesforce salesforce,
    @Nullable Concur concur) {

  public IntegrationProperties {
    scim = scim == null ? new Scim(null) : scim;
    workday = workday == null ? new Workday(null, null, null, null) : workday;
    google = google == null ? new Google(null, null, null, null, null) : google;
    microsoft = microsoft == null ? new Microsoft(null, null, null, null, null, null) : microsoft;
    salesforce = salesforce == null ? new Salesforce(null, null, null, null, null) : salesforce;
    concur = concur == null ? new Concur(null, null, null, null, null) : concur;
  }

  /**
   * @param tokens per-tenant bearer tokens the identity provider presents on /scim/v2 (tenant id ->
   *     token); a tenant without a token cannot be provisioned
   */
  public record Scim(@Nullable Map<String, String> tokens) {
    public Scim {
      tokens = tokens == null ? Map.of() : Map.copyOf(tokens);
    }
  }

  /**
   * Workday Report-as-a-Service: a custom report URL answered with basic authentication.
   *
   * @param reportUrl e.g.
   *     https://wd2-impl-services1.workday.com/ccx/service/customreport2/acme/ISU/Travel_Employees
   */
  public record Workday(
      @Nullable String reportUrl,
      @Nullable String username,
      @Nullable String password,
      @Nullable Duration timeout) {
    public Workday {
      timeout = timeout == null ? Duration.ofSeconds(60) : timeout;
    }

    public boolean configured() {
      return reportUrl != null && !reportUrl.isBlank() && password != null && !password.isBlank();
    }
  }

  /**
   * Google Workspace calendar through a service account with domain-wide delegation.
   *
   * @param privateKey the service account's PKCS#8 private key (PEM)
   */
  public record Google(
      @Nullable String serviceAccountEmail,
      @Nullable String privateKey,
      @Nullable String tokenUrl,
      @Nullable String baseUrl,
      @Nullable Duration timeout) {
    public Google {
      tokenUrl =
          tokenUrl == null || tokenUrl.isBlank() ? "https://oauth2.googleapis.com/token" : tokenUrl;
      baseUrl = baseUrl == null || baseUrl.isBlank() ? "https://www.googleapis.com" : baseUrl;
      timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
    }

    public boolean configured() {
      return serviceAccountEmail != null
          && !serviceAccountEmail.isBlank()
          && privateKey != null
          && !privateKey.isBlank();
    }
  }

  /** Microsoft 365 calendars through Microsoft Graph with an application (client credentials). */
  public record Microsoft(
      @Nullable String tenantId,
      @Nullable String clientId,
      @Nullable String clientSecret,
      @Nullable String loginUrl,
      @Nullable String baseUrl,
      @Nullable Duration timeout) {
    public Microsoft {
      loginUrl =
          loginUrl == null || loginUrl.isBlank() ? "https://login.microsoftonline.com" : loginUrl;
      baseUrl = baseUrl == null || baseUrl.isBlank() ? "https://graph.microsoft.com" : baseUrl;
      timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
    }

    public boolean configured() {
      return tenantId != null
          && !tenantId.isBlank()
          && clientId != null
          && !clientId.isBlank()
          && clientSecret != null
          && !clientSecret.isBlank();
    }
  }

  /** Salesforce through a connected app's client-credentials flow. */
  public record Salesforce(
      @Nullable String instanceUrl,
      @Nullable String clientId,
      @Nullable String clientSecret,
      @Nullable String apiVersion,
      @Nullable Duration timeout) {
    public Salesforce {
      apiVersion = apiVersion == null || apiVersion.isBlank() ? "v61.0" : apiVersion;
      timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
    }

    public boolean configured() {
      return instanceUrl != null
          && !instanceUrl.isBlank()
          && clientId != null
          && !clientId.isBlank()
          && clientSecret != null
          && !clientSecret.isBlank();
    }
  }

  /** SAP Concur through a company refresh token. */
  public record Concur(
      @Nullable String baseUrl,
      @Nullable String clientId,
      @Nullable String clientSecret,
      @Nullable String refreshToken,
      @Nullable Duration timeout) {
    public Concur {
      baseUrl =
          baseUrl == null || baseUrl.isBlank() ? "https://us.api.concursolutions.com" : baseUrl;
      timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
    }

    public boolean configured() {
      return clientId != null
          && !clientId.isBlank()
          && clientSecret != null
          && !clientSecret.isBlank()
          && refreshToken != null
          && !refreshToken.isBlank();
    }
  }
}
