package io.travelos.context.source.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import io.travelos.common.tenant.TenantId;
import io.travelos.context.IntegrationProperties;
import io.travelos.context.model.Connector;
import io.travelos.context.model.ConnectorKind;
import io.travelos.context.source.CalendarEvent;
import io.travelos.context.source.CrmRecord;
import io.travelos.context.source.ExpenseRecord;
import io.travelos.context.source.HrisRecord;
import io.travelos.context.source.SourceException;
import io.travelos.context.source.SourcePage;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Phase 7: the genuine enterprise adapters against the documented shapes of their providers, on a
 * scripted HTTP server. Each proves the credential flow, the mapping of one page, the incremental
 * checkpoint, and that a revoked credential is final while an outage is retried. No provider is
 * contacted; live verification needs a customer tenant and is recorded as blocked.
 */
class LiveSourcesContractTest {
  private static final Instant NOW = Instant.parse("2026-09-24T12:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  private static Connector connector(ConnectorKind kind, String provider, String configJson) {
    return new Connector(
        "con_1",
        TenantId.of("acme"),
        kind,
        provider,
        Connector.Status.ENABLED,
        configJson,
        "",
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        0,
        "human/carol",
        NOW,
        NOW);
  }

  @Test
  void workdayRaasReadsTheWholeReportUnderBasicAuth() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    IntegrationProperties.Workday props =
        new IntegrationProperties.Workday(
            "https://wd.test/ccx/service/customreport2/acme/ISU/Travel_Employees",
            "ISU_travel",
            "s3cret",
            null);
    WorkdayHrisSource source = new WorkdayHrisSource(builder, props, CLOCK);
    server
        .expect(
            requestTo(
                "https://wd.test/ccx/service/customreport2/acme/ISU/Travel_Employees?format=json"))
        .andExpect(
            header(
                "Authorization",
                "Basic " + Base64.getEncoder().encodeToString("ISU_travel:s3cret".getBytes())))
        .andRespond(
            withSuccess(
                """
                {"Report_Entry":[
                  {"Employee_ID":"emp_2001","primaryWorkEmail":"zoe@globex.example","Legal_Name":"Zoe Park","Location_IATA":"SFO","Time_Zone":"America/Los_Angeles","Manager_ID":"emp_2002","Active_Status":"1","Cost_Center_ID":"cc_7","Supervisory_Organization_ID":"so_1","Company_ID":"co_1"},
                  {"Employee_ID":"emp_2009","primaryWorkEmail":"left@globex.example","Legal_Name":"Left Person","Location":"","Active_Status":"0"}]}
                """,
                MediaType.APPLICATION_JSON));
    SourcePage<HrisRecord> page =
        source.fetch(connector(ConnectorKind.HRIS, "workday", "{}"), "", "");
    assertThat(page.done()).isTrue();
    assertThat(page.watermark()).isEqualTo(NOW.toString());
    assertThat(page.entries()).hasSize(2);
    HrisRecord zoe = page.entries().get(0).item();
    assertThat(zoe.employeeId()).isEqualTo("emp_2001");
    assertThat(zoe.workLocation()).isEqualTo("SFO");
    assertThat(zoe.managerEmployeeId()).isEqualTo("emp_2002");
    assertThat(zoe.costCenterId()).isEqualTo("cc_7");
    assertThat(zoe.active()).isTrue();
    assertThat(page.entries().get(1).item().active()).isFalse();
    assertThat(page.entries().get(1).item().workLocation()).isEqualTo("UNK");
    server.verify();
    // a revoked integration user is final; an outage is retried
    server.reset();
    server
        .expect(requestTo(Matchers.startsWith("https://wd.test/")))
        .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
    assertThatThrownBy(() -> source.fetch(connector(ConnectorKind.HRIS, "workday", "{}"), "", ""))
        .isInstanceOfSatisfying(
            SourceException.class,
            e -> {
              assertThat(e.code()).isEqualTo("CREDENTIALS_REVOKED");
              assertThat(e.retryable()).isFalse();
            });
    server.reset();
    server
        .expect(requestTo(Matchers.startsWith("https://wd.test/")))
        .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
    assertThatThrownBy(() -> source.fetch(connector(ConnectorKind.HRIS, "workday", "{}"), "", ""))
        .isInstanceOfSatisfying(SourceException.class, e -> assertThat(e.retryable()).isTrue());
  }

  @Test
  void googleCalendarSignsAServiceAccountAssertionAndKeepsASyncTokenPerUser() throws Exception {
    KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
    gen.initialize(2048);
    KeyPair pair = gen.generateKeyPair();
    String pem =
        "-----BEGIN PRIVATE KEY-----\n"
            + Base64.getMimeEncoder(64, "\n".getBytes())
                .encodeToString(pair.getPrivate().getEncoded())
            + "\n-----END PRIVATE KEY-----\n";
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    IntegrationProperties.Google props =
        new IntegrationProperties.Google(
            "travelos@acme-sa.iam.gserviceaccount.com",
            pem,
            "https://oauth.test/token",
            "https://calendar.test",
            null);
    GoogleCalendarSource source = new GoogleCalendarSource(builder, props, CLOCK);
    Connector c =
        connector(
            ConnectorKind.CALENDAR,
            "google-workspace",
            "{\"users\":[\"alice@acme.example\",\"bob@acme.example\"]}");
    server
        .expect(requestTo("https://oauth.test/token"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(
            r ->
                assertThat(r.getBody().toString())
                    .contains("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer")
                    .contains("assertion=eyJ"))
        .andRespond(
            withSuccess(
                "{\"access_token\":\"ya29.alice\",\"expires_in\":3599}",
                MediaType.APPLICATION_JSON));
    server
        .expect(
            requestTo(
                Matchers.startsWith(
                    "https://calendar.test/calendar/v3/calendars/primary/events?singleEvents=true&showDeleted=true&maxResults=250&timeMin=")))
        .andExpect(header("Authorization", "Bearer ya29.alice"))
        .andRespond(
            withSuccess(
                """
            {"items":[
              {"id":"evt1","status":"confirmed","updated":"2026-09-20T10:00:00.000Z","summary":"Customer visit: Globex","organizer":{"email":"alice@acme.example"},
               "start":{"dateTime":"2026-10-06T14:00:00-07:00","timeZone":"America/Los_Angeles"},"end":{"dateTime":"2026-10-06T16:00:00-07:00","timeZone":"America/Los_Angeles"},
               "location":"Globex HQ, 1 Market St, San Francisco","attendees":[{"email":"alice@acme.example","responseStatus":"accepted"},{"email":"zoe@globex.example","responseStatus":"needsAction"}]},
              {"id":"evt2","status":"cancelled","updated":"2026-09-21T10:00:00.000Z"}],
             "nextSyncToken":"sync-alice-1"}
            """,
                MediaType.APPLICATION_JSON));
    SourcePage<CalendarEvent> first = source.fetch(c, "", "");
    assertThat(first.entries()).hasSize(2);
    CalendarEvent visit = first.entries().get(0).item();
    assertThat(visit.title()).isEqualTo("Customer visit: Globex");
    assertThat(visit.start()).isEqualTo(Instant.parse("2026-10-06T21:00:00Z"));
    assertThat(visit.attendanceMode()).isEqualTo("IN_PERSON");
    assertThat(visit.attendees())
        .extracting(CalendarEvent.Attendee::status)
        .containsExactly("ACCEPTED", "NEEDS_ACTION");
    assertThat(first.entries().get(1).deleted()).isTrue();
    assertThat(first.done()).as("bob is next").isFalse();
    assertThat(first.nextCursor()).isEqualTo("u:1|p:");
    assertThat(first.watermark()).isEqualTo("{\"alice@acme.example\":\"sync-alice-1\"}");
    server.verify();
    // the next run for alice uses her sync token; the token endpoint is not asked again while
    // cached
    server.reset();
    server
        .expect(
            requestTo(
                "https://calendar.test/calendar/v3/calendars/primary/events?singleEvents=true&showDeleted=true&maxResults=250&syncToken=sync-alice-1"))
        .andRespond(
            withSuccess(
                "{\"items\":[],\"nextSyncToken\":\"sync-alice-2\"}", MediaType.APPLICATION_JSON));
    SourcePage<CalendarEvent> incremental = source.fetch(c, first.watermark(), "");
    assertThat(incremental.entries()).isEmpty();
    assertThat(incremental.watermark()).contains("sync-alice-2");
    server.verify();
    // an expired sync token restarts that user from a full window, without failing the run
    server.reset();
    server
        .expect(requestTo(Matchers.containsString("syncToken=sync-alice-2")))
        .andRespond(withStatus(HttpStatus.GONE));
    SourcePage<CalendarEvent> restarted = source.fetch(c, incremental.watermark(), "");
    assertThat(restarted.entries()).isEmpty();
    assertThat(restarted.nextCursor()).isEqualTo("u:0|p:");
    assertThat(restarted.watermark()).doesNotContain("alice@acme.example");
  }

  @Test
  void microsoftGraphUsesClientCredentialsAndDeltaLinks() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    IntegrationProperties.Microsoft props =
        new IntegrationProperties.Microsoft(
            "tenant-guid",
            "app-id",
            "app-secret",
            "https://login.test",
            "https://graph.test",
            null);
    GraphCalendarSource source = new GraphCalendarSource(builder, props, CLOCK);
    Connector c =
        connector(ConnectorKind.CALENDAR, "microsoft-365", "{\"users\":[\"alice@acme.example\"]}");
    server
        .expect(requestTo("https://login.test/tenant-guid/oauth2/v2.0/token"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(
            r ->
                assertThat(r.getBody().toString())
                    .contains("grant_type=client_credentials")
                    .contains("client_id=app-id")
                    .contains("scope=https%3A%2F%2Fgraph.test%2F.default"))
        .andRespond(
            withSuccess(
                "{\"access_token\":\"graph-token\",\"expires_in\":3599}",
                MediaType.APPLICATION_JSON));
    server
        .expect(
            requestTo(
                Matchers.startsWith(
                    "https://graph.test/v1.0/users/alice@acme.example/calendarView/delta?startDateTime=")))
        .andExpect(header("Authorization", "Bearer graph-token"))
        .andRespond(
            withSuccess(
                """
            {"value":[{"id":"AAMk1","subject":"Board offsite","lastModifiedDateTime":"2026-09-20T10:00:00Z","isCancelled":false,"isOnlineMeeting":false,
              "organizer":{"emailAddress":{"address":"alice@acme.example"}},"attendees":[{"emailAddress":{"address":"bob@acme.example"},"status":{"response":"tentativelyAccepted"}}],
              "start":{"dateTime":"2026-11-03T09:00:00.0000000","timeZone":"UTC"},"end":{"dateTime":"2026-11-03T17:00:00.0000000","timeZone":"UTC"},"location":{"displayName":"Chicago office"}}],
             "@odata.nextLink":"https://graph.test/v1.0/users/alice@acme.example/calendarView/delta?$skiptoken=abc"}
            """,
                MediaType.APPLICATION_JSON));
    SourcePage<CalendarEvent> page1 = source.fetch(c, "", "");
    assertThat(page1.entries()).hasSize(1);
    CalendarEvent offsite = page1.entries().get(0).item();
    assertThat(offsite.title()).isEqualTo("Board offsite");
    assertThat(offsite.start()).isEqualTo(Instant.parse("2026-11-03T09:00:00Z"));
    assertThat(offsite.attendees().get(0).status()).isEqualTo("TENTATIVE");
    assertThat(offsite.attendanceMode()).isEqualTo("IN_PERSON");
    assertThat(page1.done()).isFalse();
    assertThat(page1.nextCursor())
        .isEqualTo(
            "u:0|p:https://graph.test/v1.0/users/alice@acme.example/calendarView/delta?$skiptoken=abc");
    server.verify();
    server.reset();
    server
        .expect(
            requestTo(
                "https://graph.test/v1.0/users/alice@acme.example/calendarView/delta?$skiptoken=abc"))
        .andRespond(
            withSuccess(
                "{\"value\":[{\"id\":\"AAMk2\",\"@removed\":{\"reason\":\"deleted\"}}],\"@odata.deltaLink\":\"https://graph.test/v1.0/users/alice@acme.example/calendarView/delta?$deltatoken=xyz\"}",
                MediaType.APPLICATION_JSON));
    SourcePage<CalendarEvent> page2 = source.fetch(c, "", page1.nextCursor());
    assertThat(page2.entries().get(0).deleted()).isTrue();
    assertThat(page2.done()).isTrue();
    assertThat(page2.watermark()).contains("$deltatoken=xyz");
    server.verify();
    server.reset();
    server
        .expect(requestTo(Matchers.startsWith("https://graph.test/")))
        .andRespond(withStatus(HttpStatus.FORBIDDEN));
    assertThatThrownBy(() -> source.fetch(c, page2.watermark(), ""))
        .isInstanceOfSatisfying(
            SourceException.class, e -> assertThat(e.code()).isEqualTo("CREDENTIALS_REVOKED"));
  }

  @Test
  void salesforceQueriesEventsChangedSinceTheWatermarkAndPagesByNextRecordsUrl() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    IntegrationProperties.Salesforce props =
        new IntegrationProperties.Salesforce("https://acme.sf.test", "cid", "csecret", null, null);
    SalesforceCrmSource source = new SalesforceCrmSource(builder, props, CLOCK);
    Connector c = connector(ConnectorKind.CRM, "salesforce", "{}");
    server
        .expect(requestTo("https://acme.sf.test/services/oauth2/token"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(
            withSuccess(
                "{\"access_token\":\"sf-token\",\"instance_url\":\"https://acme.sf.test\"}",
                MediaType.APPLICATION_JSON));
    server
        .expect(
            requestTo(
                Matchers.startsWith("https://acme.sf.test/services/data/v61.0/query?q=SELECT")))
        .andExpect(
            r ->
                assertThat(r.getURI().toString())
                    .contains("LastModifiedDate+%3E+2026-09-01T00%3A00%3A00Z")
                    .contains("FROM+Event"))
        .andExpect(header("Authorization", "Bearer sf-token"))
        .andRespond(
            withSuccess(
                """
            {"totalSize":2,"done":false,"nextRecordsUrl":"/services/data/v61.0/query/01gxx-2000",
             "records":[
              {"Id":"00U1","Subject":"On-site workshop","Location":"Globex HQ, San Francisco","StartDateTime":"2026-10-06T17:00:00.000+0000","EndDateTime":"2026-10-06T21:00:00.000+0000","IsDeleted":false,"LastModifiedDate":"2026-09-10T08:00:00.000+0000","Owner":{"Email":"alice@acme.example"},"Account":{"Name":"Globex","BillingCity":"San Francisco"}},
              {"Id":"00U2","Subject":"Zoom check-in","Location":"https://zoom.us/j/1","StartDateTime":"2026-10-07T17:00:00.000+0000","EndDateTime":"2026-10-07T17:30:00.000+0000","IsDeleted":false,"LastModifiedDate":"2026-09-12T08:00:00.000+0000","Owner":{"Email":"alice@acme.example"},"Account":{"Name":"Globex","BillingCity":"San Francisco"}}]}
            """,
                MediaType.APPLICATION_JSON));
    SourcePage<CrmRecord> page = source.fetch(c, "2026-09-01T00:00:00Z", "");
    assertThat(page.entries()).hasSize(2);
    CrmRecord workshop = page.entries().get(0).item();
    assertThat(workshop.kind()).isEqualTo("VISIT");
    assertThat(workshop.onSite()).isTrue();
    assertThat(workshop.accountCity()).isEqualTo("San Francisco");
    assertThat(workshop.scheduledStart()).isEqualTo(Instant.parse("2026-10-06T17:00:00Z"));
    assertThat(page.entries().get(1).item().onSite()).as("a video call is not a visit").isFalse();
    assertThat(page.done()).isFalse();
    assertThat(page.nextCursor()).isEqualTo("/services/data/v61.0/query/01gxx-2000");
    assertThat(page.watermark()).isEqualTo("2026-09-12T08:00:00Z");
    server.verify();
    server.reset();
    server
        .expect(requestTo("https://acme.sf.test/services/data/v61.0/query/01gxx-2000"))
        .andRespond(withSuccess("{\"done\":true,\"records\":[]}", MediaType.APPLICATION_JSON));
    assertThat(source.fetch(c, "2026-09-01T00:00:00Z", page.nextCursor()).done()).isTrue();
    server.verify();
    server.reset();
    server
        .expect(requestTo(Matchers.startsWith("https://acme.sf.test/")))
        .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
    assertThatThrownBy(() -> source.fetch(c, "", ""))
        .isInstanceOfSatisfying(
            SourceException.class,
            e -> {
              assertThat(e.code()).isEqualTo("RATE_LIMITED");
              assertThat(e.retryable()).isTrue();
            });
  }

  @Test
  void concurRefreshesATokenAndReadsReportsModifiedSinceTheWatermark() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    IntegrationProperties.Concur props =
        new IntegrationProperties.Concur(
            "https://concur.test", "cid", "csecret", "refresh-1", null);
    ConcurExpenseSource source = new ConcurExpenseSource(builder, props, CLOCK);
    Connector c = connector(ConnectorKind.EXPENSE, "sap-concur", "{}");
    server
        .expect(requestTo("https://concur.test/oauth2/v0/token"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(
            r ->
                assertThat(r.getBody().toString())
                    .contains("grant_type=refresh_token")
                    .contains("refresh_token=refresh-1"))
        .andRespond(
            withSuccess(
                "{\"access_token\":\"concur-token\",\"expires_in\":3600}",
                MediaType.APPLICATION_JSON));
    server
        .expect(
            requestTo(
                "https://concur.test/api/v3.0/expense/reports?limit=100&modifiedafter=2026-09-01T00:00:00"))
        .andExpect(header("Authorization", "Bearer concur-token"))
        .andRespond(
            withSuccess(
                """
            {"Items":[{"ID":"RPT1","Name":"SFO customer trip","Total":"1234.56","CurrencyCode":"USD","OwnerLoginID":"alice@acme.example","UserDefinedDate":"2026-10-06T00:00:00","SubmitDate":"2026-10-09T00:00:00","LastModifiedDate":"2026-10-09T12:00:00"}],
             "NextPage":"https://concur.test/api/v3.0/expense/reports?limit=100&offset=100&modifiedafter=2026-09-01T00:00:00"}
            """,
                MediaType.APPLICATION_JSON));
    SourcePage<ExpenseRecord> page = source.fetch(c, "2026-09-01T00:00:00Z", "");
    assertThat(page.entries()).hasSize(1);
    ExpenseRecord report = page.entries().get(0).item();
    assertThat(report.kind()).isEqualTo("TRIP_REPORT");
    assertThat(report.amountMinor()).isEqualTo(123456);
    assertThat(report.employeeEmail()).isEqualTo("alice@acme.example");
    assertThat(report.startDate()).hasToString("2026-10-06");
    assertThat(report.endDate()).hasToString("2026-10-09");
    assertThat(page.done()).isFalse();
    assertThat(page.nextCursor())
        .startsWith("https://concur.test/api/v3.0/expense/reports?limit=100&offset=100");
    assertThat(page.watermark()).isEqualTo("2026-10-09T12:00:00Z");
    server.verify();
  }
}
