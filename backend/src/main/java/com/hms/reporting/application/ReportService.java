package com.hms.reporting.application;

import com.hms.audit.api.AuditEntry;
import com.hms.audit.api.AuditOutcome;
import com.hms.audit.api.AuditRecorder;
import com.hms.reporting.infrastructure.ReportRepository;
import com.hms.shared.domain.NotFoundException;
import com.hms.shared.tenancy.OrganizationPrincipal;
import java.time.Clock;
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
                    ORDER BY day, practitioner_name"""));

    private final ReportRepository repository;
    private final AuditRecorder audit;
    private final Clock clock;

    public ReportService(ReportRepository repository, AuditRecorder audit, Clock clock) {
        this.repository = repository;
        this.audit = audit;
        this.clock = clock;
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
