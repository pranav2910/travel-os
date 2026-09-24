package io.travelos.context.source.live;

import io.travelos.context.IntegrationProperties;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Phase 7: the genuine enterprise adapters exist only when their credentials do (the secrets
 * mechanism, never a connector's config). Each registers itself in the SourceRegistry under its
 * provider name; a connector for that provider can then be created and synchronized like the
 * sandbox ones. Nothing here is contacted at startup.
 */
@Configuration(proxyBeanMethods = false)
class LiveIntegrationConfiguration {
  private static final Logger log = LoggerFactory.getLogger(LiveIntegrationConfiguration.class);

  @Bean
  @ConditionalOnExpression(
      "'${travelos.context.integrations.workday.report-url:}' != '' && '${travelos.context.integrations.workday.password:}' != ''")
  WorkdayHrisSource workdayHrisSource(
      IntegrationProperties p, RestClient.Builder builder, Clock clock) {
    log.info("workday HRIS adapter registered ({})", p.workday().reportUrl());
    return new WorkdayHrisSource(builder, p.workday(), clock);
  }

  @Bean
  @ConditionalOnExpression(
      "'${travelos.context.integrations.google.service-account-email:}' != '' && '${travelos.context.integrations.google.private-key:}' != ''")
  GoogleCalendarSource googleCalendarSource(
      IntegrationProperties p, RestClient.Builder builder, Clock clock) {
    log.info("google workspace calendar adapter registered ({})", p.google().serviceAccountEmail());
    return new GoogleCalendarSource(builder, p.google(), clock);
  }

  @Bean
  @ConditionalOnExpression(
      "'${travelos.context.integrations.microsoft.tenant-id:}' != '' && '${travelos.context.integrations.microsoft.client-secret:}' != ''")
  GraphCalendarSource graphCalendarSource(
      IntegrationProperties p, RestClient.Builder builder, Clock clock) {
    log.info("microsoft 365 calendar adapter registered (tenant {})", p.microsoft().tenantId());
    return new GraphCalendarSource(builder, p.microsoft(), clock);
  }

  @Bean
  @ConditionalOnExpression(
      "'${travelos.context.integrations.salesforce.instance-url:}' != '' && '${travelos.context.integrations.salesforce.client-secret:}' != ''")
  SalesforceCrmSource salesforceCrmSource(
      IntegrationProperties p, RestClient.Builder builder, Clock clock) {
    log.info("salesforce CRM adapter registered ({})", p.salesforce().instanceUrl());
    return new SalesforceCrmSource(builder, p.salesforce(), clock);
  }

  @Bean
  @ConditionalOnExpression(
      "'${travelos.context.integrations.concur.client-id:}' != '' && '${travelos.context.integrations.concur.refresh-token:}' != ''")
  ConcurExpenseSource concurExpenseSource(
      IntegrationProperties p, RestClient.Builder builder, Clock clock) {
    log.info("sap concur expense adapter registered ({})", p.concur().baseUrl());
    return new ConcurExpenseSource(builder, p.concur(), clock);
  }
}
