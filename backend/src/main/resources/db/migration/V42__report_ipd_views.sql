-- [E9-2] IPD report read models. Design: database-design.md section 20v.
-- Runs as hms_owner (ADR-0015). security_invoker = true: RLS of the underlying tables applies to hms_app (see V34, V41).

-- Admissions in progress with the bed they currently occupy.
CREATE VIEW hms.report_active_admission_v WITH (security_invoker = true) AS
SELECT a.organization_id,
       a.admission_no,
       p.uhid,
       p.full_name AS patient_name,
       w.name AS ward_name,
       b.bed_no,
       pr.full_name AS doctor_name,
       a.status,
       a.admitted_at
FROM hms.admission a
JOIN hms.bed_assignment ba ON ba.organization_id = a.organization_id AND ba.admission_id = a.id
                          AND ba.released_at IS NULL
JOIN hms.bed b ON b.organization_id = ba.organization_id AND b.id = ba.bed_id
JOIN hms.ward w ON w.organization_id = b.organization_id AND w.id = b.ward_id
JOIN hms.patient p ON p.organization_id = a.organization_id AND p.id = a.patient_id
JOIN hms.practitioner pr ON pr.organization_id = a.organization_id AND pr.id = a.admitting_doctor_id
WHERE a.status IN ('ADMITTED', 'DISCHARGE_INITIATED');

-- Bed counts per ACTIVE ward. occupied = beds with an open assignment; out_of_service = OUT_OF_SERVICE beds
-- without one; available = the rest.
CREATE VIEW hms.report_bed_occupancy_v WITH (security_invoker = true) AS
SELECT w.organization_id,
       w.name AS ward_name,
       count(b.id) AS total_beds,
       count(b.id) FILTER (WHERE ba.id IS NOT NULL) AS occupied,
       count(b.id) FILTER (WHERE ba.id IS NULL AND b.status = 'OUT_OF_SERVICE') AS out_of_service,
       count(b.id) FILTER (WHERE ba.id IS NULL AND b.status <> 'OUT_OF_SERVICE') AS available
FROM hms.ward w
LEFT JOIN hms.bed b ON b.organization_id = w.organization_id AND b.ward_id = w.id
LEFT JOIN hms.bed_assignment ba ON ba.organization_id = b.organization_id AND ba.bed_id = b.id
                               AND ba.released_at IS NULL
GROUP BY w.organization_id, w.id, w.name, w.status
HAVING w.status = 'ACTIVE' OR count(ba.id) > 0; -- retired wards still holding patients stay visible

CREATE VIEW hms.report_discharge_v WITH (security_invoker = true) AS
SELECT a.organization_id,
       (a.discharged_at AT TIME ZONE o.timezone)::date AS day
FROM hms.admission a
JOIN hms.organization o ON o.id = a.organization_id
WHERE a.status = 'DISCHARGED';

-- Organization-local today, for snapshot CSV file names.
CREATE VIEW hms.report_org_today_v WITH (security_invoker = true) AS
SELECT o.id AS organization_id, (now() AT TIME ZONE o.timezone)::date AS today
FROM hms.organization o;

GRANT SELECT ON hms.report_active_admission_v, hms.report_bed_occupancy_v, hms.report_discharge_v,
    hms.report_org_today_v TO hms_app;
