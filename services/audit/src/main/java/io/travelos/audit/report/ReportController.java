package io.travelos.audit.report;

import io.travelos.spring.web.auth.RequestPrincipal;
import io.travelos.spring.web.error.ApiException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 9: spend, outcomes, exceptions and supplier reports for FINANCE and TRAVEL_ADMIN, as JSON
 * or CSV ({@code format=csv}). Periods default to the last 90 days and may not exceed 400 days.
 */
@RestController
@RequestMapping(path = "/api/v1/reports")
public class ReportController {
  private final ReportService reports;

  public ReportController(ReportService reports) {
    this.reports = reports;
  }

  @GetMapping("/spend")
  public ResponseEntity<?> spend(
      @AuthenticationPrincipal RequestPrincipal me,
      @RequestParam(required = false) @Nullable Instant from,
      @RequestParam(required = false) @Nullable Instant to,
      @RequestParam(defaultValue = "costCenter") String groupBy,
      @RequestParam(defaultValue = "json") String format) {
    ReportService.Period p = period(me, from, to);
    List<ReportService.SpendRow> rows = reports.spend(me.tenant(), p, groupBy);
    if ("csv".equalsIgnoreCase(format)) {
      List<List<Object>> table = new ArrayList<>();
      table.add(
          List.of(
              groupBy,
              "trips",
              "currency",
              "bookedMinor",
              "capturedMinor",
              "refundedMinor",
              "creditMinor",
              "incrementalMinor",
              "netMinor"));
      rows.forEach(
          r ->
              table.add(
                  List.of(
                      r.key(),
                      r.trips(),
                      r.currency(),
                      r.bookedMinor(),
                      r.capturedMinor(),
                      r.refundedMinor(),
                      r.creditMinor(),
                      r.incrementalMinor(),
                      r.netMinor())));
      return csv("spend-by-" + groupBy, table);
    }
    return ResponseEntity.ok(
        Map.of("from", p.from(), "to", p.to(), "groupBy", groupBy, "rows", rows));
  }

  @GetMapping("/outcomes")
  public ResponseEntity<?> outcomes(
      @AuthenticationPrincipal RequestPrincipal me,
      @RequestParam(required = false) @Nullable Instant from,
      @RequestParam(required = false) @Nullable Instant to,
      @RequestParam(defaultValue = "json") String format) {
    ReportService.Period p = period(me, from, to);
    ReportService.Outcomes o = reports.outcomes(me.tenant(), p);
    if ("csv".equalsIgnoreCase(format)) {
      List<List<Object>> table = new ArrayList<>();
      table.add(List.of("metric", "value"));
      table.add(List.of("created", o.created()));
      table.add(List.of("booked", o.booked()));
      table.add(List.of("cancelled", o.cancelled()));
      table.add(List.of("failed", o.failed()));
      table.add(List.of("completed", o.completed()));
      table.add(List.of("bookingRate", nz(o.bookingRate())));
      table.add(List.of("medianHoursToBook", nz(o.medianHoursToBook())));
      table.add(List.of("disruptions", o.disruptions()));
      table.add(List.of("recoveriesResolved", o.recoveriesResolved()));
      table.add(List.of("recoveriesFailed", o.recoveriesFailed()));
      table.add(List.of("autonomousRecoveries", o.autonomousRecoveries()));
      table.add(List.of("avgRecoverySeconds", nz(o.avgRecoverySeconds())));
      table.add(List.of("advisories", o.advisories()));
      table.add(List.of("checkins", o.checkins()));
      return csv("outcomes", table);
    }
    return ResponseEntity.ok(Map.of("from", p.from(), "to", p.to(), "outcomes", o));
  }

  @GetMapping("/exceptions")
  public ResponseEntity<?> exceptions(
      @AuthenticationPrincipal RequestPrincipal me,
      @RequestParam(required = false) @Nullable Instant from,
      @RequestParam(required = false) @Nullable Instant to,
      @RequestParam(defaultValue = "json") String format) {
    ReportService.Period p = period(me, from, to);
    ReportService.Exceptions x = reports.exceptions(me.tenant(), p);
    if ("csv".equalsIgnoreCase(format)) {
      List<List<Object>> table = new ArrayList<>();
      table.add(List.of("section", "key", "value"));
      x.policyViolationsByReason()
          .forEach(r -> table.add(List.of("policyViolation", r.key(), r.count())));
      table.add(List.of("approvals", "requested", x.approvalsRequested()));
      table.add(List.of("approvals", "approved", x.approvalsApproved()));
      table.add(List.of("approvals", "rejected", x.approvalsRejected()));
      table.add(List.of("approvals", "escalated", x.approvalsEscalated()));
      table.add(List.of("approvals", "expired", x.approvalsExpired()));
      table.add(List.of("approvals", "avgHours", nz(x.avgApprovalHours())));
      table.add(List.of("budget", "exceeded", x.budgetExceeded()));
      x.casesByKind().forEach(r -> table.add(List.of("caseKind", r.key(), r.count())));
      x.casesByQueue().forEach(r -> table.add(List.of("caseQueue", r.key(), r.count())));
      table.add(List.of("cases", "resolved", x.casesResolved()));
      table.add(List.of("cases", "avgResolutionHours", nz(x.avgCaseResolutionHours())));
      table.add(List.of("notifications", "failed", x.notificationsFailed()));
      table.add(List.of("exposures", "compensationFailed", x.exposures()));
      return csv("exceptions", table);
    }
    return ResponseEntity.ok(Map.of("from", p.from(), "to", p.to(), "exceptions", x));
  }

  @GetMapping("/suppliers")
  public ResponseEntity<?> suppliers(
      @AuthenticationPrincipal RequestPrincipal me,
      @RequestParam(required = false) @Nullable Instant from,
      @RequestParam(required = false) @Nullable Instant to,
      @RequestParam(defaultValue = "json") String format) {
    ReportService.Period p = period(me, from, to);
    List<ReportService.SupplierRow> rows = reports.suppliers(me.tenant(), p);
    if ("csv".equalsIgnoreCase(format)) {
      List<List<Object>> table = new ArrayList<>();
      table.add(
          List.of(
              "provider",
              "ordersConfirmed",
              "itemsConfirmed",
              "itemsReleased",
              "cancellationsRefused",
              "disruptions",
              "creditsIssued",
              "creditMinor"));
      rows.forEach(
          r ->
              table.add(
                  List.of(
                      r.provider(),
                      r.ordersConfirmed(),
                      r.itemsConfirmed(),
                      r.itemsReleased(),
                      r.cancellationsRefused(),
                      r.disruptions(),
                      r.creditsIssued(),
                      r.creditMinor())));
      return csv("suppliers", table);
    }
    return ResponseEntity.ok(Map.of("from", p.from(), "to", p.to(), "rows", rows));
  }

  private static ReportService.Period period(
      RequestPrincipal me, @Nullable Instant from, @Nullable Instant to) {
    if (!me.hasAnyRole("FINANCE", "TRAVEL_ADMIN")) {
      throw new ApiException.Forbidden(
          "NOT_A_REPORT_READER", "FINANCE or TRAVEL_ADMIN role required");
    }
    Instant end = to == null ? Instant.now() : to;
    Instant start = from == null ? end.minus(Duration.ofDays(90)) : from;
    if (!end.isAfter(start)) {
      throw new ApiException.Unprocessable("PERIOD_INVALID", "to must be after from");
    }
    if (Duration.between(start, end).toDays() > 400) {
      throw new ApiException.Unprocessable("PERIOD_TOO_LONG", "a report covers at most 400 days");
    }
    return new ReportService.Period(start, end);
  }

  private static ResponseEntity<String> csv(String name, List<List<Object>> table) {
    StringBuilder out = new StringBuilder();
    for (List<Object> row : table) {
      for (int i = 0; i < row.size(); i++) {
        if (i > 0) {
          out.append(',');
        }
        String cell = String.valueOf(row.get(i));
        if (cell.contains(",") || cell.contains("\"") || cell.contains("\n")) {
          cell = "\"" + cell.replace("\"", "\"\"") + "\"";
        }
        out.append(cell);
      }
      out.append("\r\n");
    }
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType("text/csv; charset=utf-8"))
        .header("Content-Disposition", "attachment; filename=\"" + name + ".csv\"")
        .body(out.toString());
  }

  private static Object nz(@Nullable Double d) {
    return d == null ? "" : String.format(java.util.Locale.ROOT, "%.2f", d);
  }
}
