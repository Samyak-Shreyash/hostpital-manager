-- [E9-1] Operational report read models. Design: database-design.md section 20u.
-- Runs as hms_owner (ADR-0015). Reporting reads SQL views only (CLAUDE.md section 4, documented exception).
-- Each view exposes the organization-local calendar date day = (timestamp AT TIME ZONE organization.timezone)::date,
-- so a report range of local dates matches what the facility calls that day.
-- security_invoker = true: RLS of the underlying tables applies to hms_app (see V34).

CREATE VIEW hms.report_registration_v WITH (security_invoker = true) AS
SELECT p.organization_id,
       (p.created_at AT TIME ZONE o.timezone)::date AS day
FROM hms.patient p
JOIN hms.organization o ON o.id = p.organization_id;

CREATE VIEW hms.report_appointment_v WITH (security_invoker = true) AS
SELECT a.organization_id,
       (a.start_at AT TIME ZONE o.timezone)::date AS day,
       a.practitioner_id,
       pr.full_name AS practitioner_name,
       a.status
FROM hms.appointment a
JOIN hms.organization o ON o.id = a.organization_id
JOIN hms.practitioner pr ON pr.organization_id = a.organization_id AND pr.id = a.practitioner_id;

CREATE VIEW hms.report_opd_consultation_v WITH (security_invoker = true) AS
SELECT e.organization_id,
       (e.finalized_at AT TIME ZONE o.timezone)::date AS day,
       e.practitioner_id,
       pr.full_name AS practitioner_name
FROM hms.encounter e
JOIN hms.organization o ON o.id = e.organization_id
JOIN hms.practitioner pr ON pr.organization_id = e.organization_id AND pr.id = e.practitioner_id
WHERE e.status = 'FINALIZED';

GRANT SELECT ON hms.report_registration_v, hms.report_appointment_v, hms.report_opd_consultation_v TO hms_app;
