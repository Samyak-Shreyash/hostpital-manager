package com.hms.reporting.application;

import com.hms.audit.api.AuditEntry;
import com.hms.audit.api.AuditOutcome;
import com.hms.audit.api.AuditRecorder;
import com.hms.reporting.infrastructure.ReportRepository;
import com.hms.shared.domain.NotFoundException;
import com.hms.shared.tenancy.OrganizationPrincipal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs named reports over the reporting views ([E9-1]). The organization comes from the principal only; every
 * report SQL filters organization_id = :org. One REPORT_VIEWED audit event per run lists the names of the supplied
 * filters, never row data.
 */
@Service
public class ReportService {

    private record Def(List<String> columns, String sql) {
    }

    private static final Map<String, Def> REPORTS = Map.of(
            "registrations-daily", new Def(List.of("date", "count"), """
                    SELECT d::date AS date, count(r.day) AS count
                    FROM generate_series(CAST(:from AS timestamp), CAST(:to AS timestamp), interval '1 day') d
                    LEFT JOIN hms.report_registration_v r
                      ON r.organization_id = :org AND r.day = d::date
                    GROUP BY d ORDER BY d"""),
            "appointments", new Def(List.of("date", "practitionerId", "practitionerName", "status", "count"), """
                    SELECT day AS date, practitioner_id AS "practitionerId", practitioner_name AS "practitionerName",
                           status, count(*) AS count
                    FROM hms.report_appointment_v
                    WHERE organization_id = :org AND day BETWEEN :from AND :to
                    GROUP BY day, practitioner_id, practitioner_name, status
                    ORDER BY day, practitioner_name, status"""),
            "opd-consultations", new Def(List.of("date", "practitionerId", "practitionerName", "count"), """
                    SELECT day AS date, practitioner_id AS "practitionerId", practitioner_name AS "practitionerName",
                           count(*) AS count
                    FROM hms.report_opd_consultation_v
                    WHERE organization_id = :org AND day BETWEEN :from AND :to
                    GROUP BY day, practitioner_id, practitioner_name
                    ORDER BY day, practitioner_name"""),
            "admissions-active", new Def(List.of("admissionNo", "uhid", "patientName", "wardName", "bedNo",
                    "doctorName", "status", "admittedAt"), """
                    SELECT admission_no AS "admissionNo", uhid, patient_name AS "patientName",
                           ward_name AS "wardName", bed_no AS "bedNo", doctor_name AS "doctorName", status,
                           admitted_at AS "admittedAt"
                    FROM hms.report_active_admission_v
                    WHERE organization_id = :org
                    ORDER BY admitted_at, admission_no"""),
            "beds-occupancy", new Def(List.of("wardName", "totalBeds", "occupied", "outOfService", "available",
                    "occupancyPercent"), """
                    WITH w AS (
                      SELECT ward_name, total_beds, occupied, out_of_service, available, 0 AS ord
                      FROM hms.report_bed_occupancy_v WHERE organization_id = :org
                      UNION ALL
                      SELECT 'Total', coalesce(sum(total_beds), 0), coalesce(sum(occupied), 0),
                             coalesce(sum(out_of_service), 0), coalesce(sum(available), 0), 1
                      FROM hms.report_bed_occupancy_v WHERE organization_id = :org)
                    SELECT ward_name AS "wardName", total_beds::bigint AS "totalBeds", occupied::bigint AS occupied,
                           out_of_service::bigint AS "outOfService", available::bigint AS available,
                           coalesce(round(100.0 * occupied / nullif(total_beds - out_of_service, 0), 1), 0.0)::text
                             AS "occupancyPercent"
                    FROM w ORDER BY ord, ward_name"""),
            "discharges", new Def(List.of("date", "count"), """
                    SELECT d::date AS date, count(r.day) AS count
                    FROM generate_series(CAST(:from AS timestamp), CAST(:to AS timestamp), interval '1 day') d
                    LEFT JOIN hms.report_discharge_v r
                      ON r.organization_id = :org AND r.day = d::date
                    GROUP BY d ORDER BY d"""));

    private final ReportRepository repository;
    private final AuditRecorder audit;
    private final Clock clock;

    public ReportService(ReportRepository repository, AuditRecorder audit, Clock clock) {
        this.repository = repository;
        this.audit = audit;
        this.clock = clock;
    }

    /** The organization-local date, for snapshot CSV file names. */
    @Transactional(readOnly = true)
    public LocalDate localToday(OrganizationPrincipal actor) {
        return LocalDate.parse((String) repository.query(
                "SELECT CAST(today AS text) AS today FROM hms.report_org_today_v WHERE organization_id = :org",
                Map.of("org", actor.organizationId())).getFirst().get("today"));
    }

    /** rangeOrNull is null for snapshot reports. */
    @Transactional // not readOnly: Hibernate would skip flushing the audit event
    public ReportResult run(OrganizationPrincipal actor, String report, ReportRange rangeOrNull, boolean csv) {
        Def def = REPORTS.get(report);
        if (def == null) {
            throw new NotFoundException("Unknown report");
        }
        Map<String, Object> params = new HashMap<>();
        params.put("org", actor.organizationId());
        List<String> filters = new ArrayList<>();
        if (rangeOrNull != null) {
            params.put("from", rangeOrNull.from());
            params.put("to", rangeOrNull.to());
            filters.add("from");
            filters.add("to");
        }
        if (csv) {
            filters.add("format");
        }
        List<Map<String, Object>> rows = repository.query(def.sql(), params);
        audit.record(new AuditEntry(actor.organizationId(), actor.userId(), "REPORT_VIEWED", "REPORT", null,
                AuditOutcome.SUCCESS, filters));
        return new ReportResult(report, rangeOrNull == null ? null : rangeOrNull.from(),
                rangeOrNull == null ? null : rangeOrNull.to(), clock.instant(), def.columns(), rows);
    }
}
