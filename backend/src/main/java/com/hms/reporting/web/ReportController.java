package com.hms.reporting.web;

import com.hms.reporting.application.ReportRange;
import com.hms.reporting.application.ReportResult;
import com.hms.reporting.application.ReportService;
import com.hms.shared.tenancy.OrganizationPrincipal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Reports (api-design.md section 13, [E9-1]): JSON by default, CSV with Accept: text/csv. */
@RestController
class ReportController {

    static final MediaType CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);
    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;

    private final ReportService reports;

    ReportController(ReportService reports) {
        this.reports = reports;
    }

    @GetMapping(value = "/api/v1/reports/registrations/daily", produces = {JSON, "text/csv"})
    @PreAuthorize("hasAuthority('report:clinical')")
    ResponseEntity<?> registrationsDaily(@AuthenticationPrincipal OrganizationPrincipal actor,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestHeader(value = HttpHeaders.ACCEPT, required = false) String accept) {
        return ranged(actor, "registrations-daily", from, to, accept);
    }

    @GetMapping(value = "/api/v1/reports/appointments", produces = {JSON, "text/csv"})
    @PreAuthorize("hasAuthority('report:clinical')")
    ResponseEntity<?> appointments(@AuthenticationPrincipal OrganizationPrincipal actor,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestHeader(value = HttpHeaders.ACCEPT, required = false) String accept) {
        return ranged(actor, "appointments", from, to, accept);
    }

    @GetMapping(value = "/api/v1/reports/opd-consultations", produces = {JSON, "text/csv"})
    @PreAuthorize("hasAuthority('report:clinical')")
    ResponseEntity<?> opdConsultations(@AuthenticationPrincipal OrganizationPrincipal actor,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestHeader(value = HttpHeaders.ACCEPT, required = false) String accept) {
        return ranged(actor, "opd-consultations", from, to, accept);
    }

    @GetMapping(value = "/api/v1/reports/admissions/active", produces = {JSON, "text/csv"})
    @PreAuthorize("hasAuthority('report:clinical')")
    ResponseEntity<?> activeAdmissions(@AuthenticationPrincipal OrganizationPrincipal actor,
            @RequestHeader(value = HttpHeaders.ACCEPT, required = false) String accept) {
        return snapshot(actor, "admissions-active", accept);
    }

    @GetMapping(value = "/api/v1/reports/beds/occupancy", produces = {JSON, "text/csv"})
    @PreAuthorize("hasAuthority('report:clinical')")
    ResponseEntity<?> bedOccupancy(@AuthenticationPrincipal OrganizationPrincipal actor,
            @RequestHeader(value = HttpHeaders.ACCEPT, required = false) String accept) {
        return snapshot(actor, "beds-occupancy", accept);
    }

    @GetMapping(value = "/api/v1/reports/discharges", produces = {JSON, "text/csv"})
    @PreAuthorize("hasAuthority('report:clinical')")
    ResponseEntity<?> discharges(@AuthenticationPrincipal OrganizationPrincipal actor,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestHeader(value = HttpHeaders.ACCEPT, required = false) String accept) {
        return ranged(actor, "discharges", from, to, accept);
    }

    @GetMapping(value = "/api/v1/reports/revenue/daily", produces = {JSON, "text/csv"})
    @PreAuthorize("hasAuthority('report:finance')")
    ResponseEntity<?> revenueDaily(@AuthenticationPrincipal OrganizationPrincipal actor,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestHeader(value = HttpHeaders.ACCEPT, required = false) String accept) {
        return ranged(actor, "revenue-daily", from, to, accept);
    }

    @GetMapping(value = "/api/v1/reports/payments/outstanding", produces = {JSON, "text/csv"})
    @PreAuthorize("hasAuthority('report:finance')")
    ResponseEntity<?> paymentsOutstanding(@AuthenticationPrincipal OrganizationPrincipal actor,
            @RequestHeader(value = HttpHeaders.ACCEPT, required = false) String accept) {
        return snapshot(actor, "payments-outstanding", accept);
    }

    private ResponseEntity<?> ranged(OrganizationPrincipal actor, String name, LocalDate from, LocalDate to,
            String accept) {
        boolean csv = accept != null && accept.contains("text/csv");
        return respond(reports.run(actor, name, ReportRange.of(from, to), csv), csv, null);
    }

    /** Snapshot reports take no dates; their CSV file name carries the organization-local date. */
    private ResponseEntity<?> snapshot(OrganizationPrincipal actor, String name, String accept) {
        boolean csv = accept != null && accept.contains("text/csv");
        ReportResult result = reports.run(actor, name, null, csv);
        return respond(result, csv, csv ? reports.localToday(actor) : null);
    }

    /**
     * Shared by every report endpoint: JSON body, or CSV attachment named report-from-to.csv; snapshots (no from)
     * use {@code today}, the organization-local date.
     */
    static ResponseEntity<?> respond(ReportResult result, boolean csv, LocalDate today) {
        if (!csv) {
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(result);
        }
        String suffix = result.from() == null ? today.toString()
                : result.from() + "-" + result.to();
        return ResponseEntity.ok().contentType(CSV)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + result.report() + "-" + suffix + ".csv\"")
                .body(CsvWriter.write(result));
    }
}
