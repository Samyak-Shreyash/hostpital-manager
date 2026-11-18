-- ADR-0016: old/new values for a small allowlist of non-PHI code fields (user status and roles, role permissions).
-- The allowlist lives in com.hms.audit.api.AuditValueChange; this check is defence in depth so that only code
-- characters (no free text punctuation, quotes or escapes) can ever be stored. Runs as hms_owner.
ALTER TABLE hms.audit_event ADD COLUMN changed_values jsonb NOT NULL DEFAULT '[]';
ALTER TABLE hms.audit_event ADD CONSTRAINT ck_audit_event_changed_values CHECK (
    jsonb_typeof(changed_values) = 'array'
    AND jsonb_array_length(changed_values) <= 50
    AND changed_values::text ~ '^[][{}":, A-Za-z0-9_.-]*$');
