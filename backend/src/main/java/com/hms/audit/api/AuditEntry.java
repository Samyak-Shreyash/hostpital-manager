package com.hms.audit.api;

import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * One audit event as supplied by a module. Carries no values, only identifiers and the <em>names</em>
 * of changed fields (PRD AUD-2, AUD-4), so passwords, tokens, clinical free text and PHI have nowhere to go:
 * anything that does not look like a field name is rejected. The database re-checks the same shapes
 * ({@code V2__audit_event.sql}).
 *
 * @param organizationId owning organization (from the authenticated context, never from request input)
 * @param actorId        acting user, or {@code null} for the system or an unauthenticated caller
 * @param action         upper snake case, e.g. {@code PATIENT_VIEWED}, {@code LOGIN_FAILED}
 * @param entityType     upper snake case, e.g. {@code PATIENT}
 * @param entityId       affected entity, or {@code null} when there is none (e.g. failed login)
 * @param changedFields  camelCase field names, optionally dotted ({@code address.city}); at most 50,
 *                       stored de-duplicated and sorted
 */
public record AuditEntry(
        UUID organizationId,
        UUID actorId,
        String action,
        String entityType,
        UUID entityId,
        AuditOutcome outcome,
        List<String> changedFields) {

    public static final int MAX_CHANGED_FIELDS = 50;

    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{1,63}");
    private static final Pattern FIELD_NAME =
            Pattern.compile("[a-z][A-Za-z0-9]{0,63}(\\.[a-z][A-Za-z0-9]{0,63})*");

    public AuditEntry {
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(outcome, "outcome");
        requireCode(action, "action");
        requireCode(entityType, "entityType");
        TreeSet<String> fields = new TreeSet<>();
        for (String field : changedFields == null ? List.<String>of() : changedFields) {
            if (field == null || !FIELD_NAME.matcher(field).matches()) {
                // Never echo the rejected text: it may be the very value that must not be recorded.
                throw new IllegalArgumentException("changedFields must contain field names only");
            }
            fields.add(field);
        }
        if (fields.size() > MAX_CHANGED_FIELDS) {
            throw new IllegalArgumentException("at most " + MAX_CHANGED_FIELDS + " changed fields");
        }
        changedFields = List.copyOf(fields);
    }

    /** An event without field-level change metadata (views, auth events, state transitions). */
    public static AuditEntry of(UUID organizationId, UUID actorId, String action, String entityType,
            UUID entityId, AuditOutcome outcome) {
        return new AuditEntry(organizationId, actorId, action, entityType, entityId, outcome, List.of());
    }

    private static void requireCode(String value, String name) {
        if (value == null || !CODE.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " must be UPPER_SNAKE_CASE, 2-64 characters");
        }
    }
}
