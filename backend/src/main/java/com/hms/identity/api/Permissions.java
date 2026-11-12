package com.hms.identity.api;

import java.util.Set;

/**
 * The permission catalogue (ADR-0006): code-defined constants, seeded into {@code hms.permission} by
 * {@code V4__identity_rbac.sql}. Code checks these, never role names, e.g.
 * {@code @PreAuthorize("hasAuthority('patient:read')")}. Codes come from the Permission columns of
 * docs/api/api-design.md. Adding a code needs a migration that inserts it (a test compares both lists).
 */
public final class Permissions {

    public static final String ADMISSION_CREATE = "admission:create";
    public static final String ADMISSION_READ = "admission:read";
    public static final String ADMISSION_UPDATE = "admission:update";
    public static final String ADMISSION_CANCEL = "admission:cancel";
    public static final String APPOINTMENT_CREATE = "appointment:create";
    public static final String APPOINTMENT_READ = "appointment:read";
    public static final String APPOINTMENT_UPDATE = "appointment:update";
    public static final String APPOINTMENT_CANCEL = "appointment:cancel";
    public static final String APPOINTMENT_CHECK_IN = "appointment:check-in";
    public static final String APPOINTMENT_BACKDATE = "appointment:backdate";
    public static final String APPOINTMENT_OVERRIDE = "appointment:override";
    public static final String AUDIT_READ = "audit:read";
    public static final String BED_READ = "bed:read";
    public static final String BED_MANAGE = "bed:manage";
    public static final String BED_ASSIGN = "bed:assign";
    public static final String CATALOG_READ = "catalog:read";
    public static final String CATALOG_MANAGE = "catalog:manage";
    public static final String DISCHARGE_WRITE = "discharge:write";
    public static final String DISCHARGE_COMPLETE = "discharge:complete";
    public static final String DISCHARGE_DEFER_BILLING = "discharge:defer-billing";
    public static final String ENCOUNTER_CREATE = "encounter:create";
    public static final String ENCOUNTER_READ = "encounter:read";
    public static final String ENCOUNTER_UPDATE = "encounter:update";
    public static final String ENCOUNTER_UPDATE_ANY = "encounter:update-any";
    public static final String ENCOUNTER_FINALIZE = "encounter:finalize";
    public static final String ENCOUNTER_ADDENDUM = "encounter:addendum";
    public static final String INVOICE_CREATE = "invoice:create";
    public static final String INVOICE_READ = "invoice:read";
    public static final String INVOICE_UPDATE = "invoice:update";
    public static final String INVOICE_ISSUE = "invoice:issue";
    public static final String INVOICE_CREDIT = "invoice:credit";
    public static final String INVOICE_PRICE_OVERRIDE = "invoice:price-override";
    public static final String OBSERVATION_WRITE = "observation:write";
    public static final String ORG_READ = "org:read";
    public static final String ORG_UPDATE = "org:update";
    public static final String PATIENT_CREATE = "patient:create";
    public static final String PATIENT_READ = "patient:read";
    public static final String PATIENT_SEARCH = "patient:search";
    public static final String PATIENT_UPDATE = "patient:update";
    public static final String PATIENT_UPDATE_CLINICAL = "patient:update-clinical";
    public static final String PAYMENT_CREATE = "payment:create";
    public static final String PAYMENT_READ = "payment:read";
    public static final String PRACTITIONER_CREATE = "practitioner:create";
    public static final String PRACTITIONER_READ = "practitioner:read";
    public static final String PRACTITIONER_UPDATE = "practitioner:update";
    public static final String PRACTITIONER_SCHEDULE = "practitioner:schedule";
    public static final String PRESCRIPTION_CREATE = "prescription:create";
    public static final String PRESCRIPTION_READ = "prescription:read";
    public static final String PRESCRIPTION_ISSUE = "prescription:issue";
    public static final String REFUND_CREATE = "refund:create";
    public static final String REFUND_READ = "refund:read";
    public static final String REFUND_APPROVE = "refund:approve";
    public static final String REPORT_CLINICAL = "report:clinical";
    public static final String REPORT_FINANCE = "report:finance";
    public static final String ROLE_READ = "role:read";
    public static final String ROLE_MANAGE = "role:manage";
    public static final String USER_CREATE = "user:create";
    public static final String USER_READ = "user:read";
    public static final String USER_UPDATE = "user:update";
    public static final String USER_RESET_PASSWORD = "user:reset-password";
    public static final String WARD_READ = "ward:read";
    public static final String WARD_MANAGE = "ward:manage";

    /** Every code in the catalogue. */
    public static final Set<String> ALL = Set.of(
            ADMISSION_CREATE, ADMISSION_READ, ADMISSION_UPDATE, ADMISSION_CANCEL, APPOINTMENT_CREATE,
            APPOINTMENT_READ, APPOINTMENT_UPDATE, APPOINTMENT_CANCEL, APPOINTMENT_CHECK_IN,
            APPOINTMENT_BACKDATE, APPOINTMENT_OVERRIDE, AUDIT_READ, BED_READ, BED_MANAGE, BED_ASSIGN,
            CATALOG_READ, CATALOG_MANAGE, DISCHARGE_WRITE, DISCHARGE_COMPLETE,
            DISCHARGE_DEFER_BILLING, ENCOUNTER_CREATE, ENCOUNTER_READ, ENCOUNTER_UPDATE,
            ENCOUNTER_UPDATE_ANY, ENCOUNTER_FINALIZE, ENCOUNTER_ADDENDUM, INVOICE_CREATE,
            INVOICE_READ, INVOICE_UPDATE, INVOICE_ISSUE, INVOICE_CREDIT, INVOICE_PRICE_OVERRIDE,
            OBSERVATION_WRITE, ORG_READ, ORG_UPDATE, PATIENT_CREATE, PATIENT_READ, PATIENT_SEARCH,
            PATIENT_UPDATE, PATIENT_UPDATE_CLINICAL, PAYMENT_CREATE, PAYMENT_READ,
            PRACTITIONER_CREATE, PRACTITIONER_READ, PRACTITIONER_UPDATE, PRACTITIONER_SCHEDULE,
            PRESCRIPTION_CREATE, PRESCRIPTION_READ, PRESCRIPTION_ISSUE, REFUND_CREATE, REFUND_READ,
            REFUND_APPROVE, REPORT_CLINICAL, REPORT_FINANCE, ROLE_READ, ROLE_MANAGE, USER_CREATE,
            USER_READ, USER_UPDATE, USER_RESET_PASSWORD, WARD_READ, WARD_MANAGE);

    private Permissions() {
    }
}
