-- [E3-2] Patient search. Fuzzy name search uses pg_trgm (installed in V1, database-design.md section 3).
-- Runs as hms_owner. Phone and UHID lookups use ix_patient_org_phone and uq_patient_org_uhid from V10.
-- ABHA search is deferred (needs the ADR-0009 application-level crypto decision): no ABHA columns here.

CREATE INDEX ix_patient_full_name_trgm ON hms.patient USING gin (full_name hms.gin_trgm_ops);

-- A PATIENT_SEARCH audit event records how many patients came back, never the query terms (PHI).
ALTER TABLE hms.audit_event ADD COLUMN result_count integer;
ALTER TABLE hms.audit_event ADD CONSTRAINT ck_audit_event_result_count CHECK (result_count IS NULL OR result_count >= 0);
