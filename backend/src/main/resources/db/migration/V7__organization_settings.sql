-- [E2-1] Organization settings. Design: docs/architecture/database-design.md section 16. Runs as hms_owner.
-- timezone is an IANA id validated by the application (the JDK knows the list); the CHECK only bounds the shape.
-- gstin is optional; the CHECK enforces the GSTIN format (15 characters: state code, PAN, entity, Z, check digit).
-- No new grants: SELECT/INSERT/UPDATE come from the default privileges of V1, and there is still no DELETE.
ALTER TABLE hms.organization
    ADD COLUMN timezone text NOT NULL DEFAULT 'Asia/Kolkata',
    ADD COLUMN gstin    text,
    ADD COLUMN address  text;

ALTER TABLE hms.organization
    ADD CONSTRAINT ck_organization_timezone CHECK (timezone ~ '^[A-Za-z][A-Za-z0-9_+/-]{0,63}$'),
    ADD CONSTRAINT ck_organization_gstin
        CHECK (gstin IS NULL OR gstin ~ '^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$'),
    ADD CONSTRAINT ck_organization_address CHECK (address IS NULL OR char_length(address) BETWEEN 1 AND 500);
