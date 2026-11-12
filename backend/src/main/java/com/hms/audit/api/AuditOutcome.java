package com.hms.audit.api;

/** Result of the audited action (PRD AUD-2). {@code DENIED} = refused by authorization. */
public enum AuditOutcome {
    SUCCESS,
    FAILURE,
    DENIED
}
