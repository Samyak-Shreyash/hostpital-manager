package com.hms.audit.api;

/**
 * The one way modules write audit events (ADR-0007, hld.md 4). Must be called inside the caller's
 * transaction so the event commits or rolls back with the change it describes (PRD BR-B7); calling it
 * without an active transaction throws. Time, id and correlation id are filled in by the recorder.
 */
public interface AuditRecorder {

    void record(AuditEntry entry);
}
