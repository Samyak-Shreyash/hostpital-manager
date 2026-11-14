-- [E3-2] ABHA number capture (ADR-0009, owner design 2026-11-13). Runs as hms_owner.
-- The number is stored only encrypted (AES-256-GCM: 12-byte nonce || ciphertext+tag) with the id of the key that
-- encrypted it; exact search uses abha_hash = HMAC-SHA256(separate key, 14 digits); only the last 4 digits are kept
-- in clear for display. All four columns are set together or all null. Existing hms_app table grants cover them.

ALTER TABLE hms.patient
    ADD COLUMN abha_encrypted bytea,
    ADD COLUMN abha_key_id    varchar(32),
    ADD COLUMN abha_hash      bytea,
    ADD COLUMN abha_last4     char(4),
    ADD CONSTRAINT ck_patient_abha_all_or_none CHECK (
        (abha_encrypted IS NULL AND abha_key_id IS NULL AND abha_hash IS NULL AND abha_last4 IS NULL)
        OR (abha_encrypted IS NOT NULL AND abha_key_id IS NOT NULL AND abha_hash IS NOT NULL
            AND abha_last4 IS NOT NULL)),
    ADD CONSTRAINT ck_patient_abha_last4 CHECK (abha_last4 ~ '^[0-9]{4}$'),
    ADD CONSTRAINT ck_patient_abha_hash CHECK (octet_length(abha_hash) = 32);

-- One patient per ABHA number per organization; also serves the exact-match search.
CREATE UNIQUE INDEX uq_patient_org_abha_hash ON hms.patient (organization_id, abha_hash)
    WHERE abha_hash IS NOT NULL;
