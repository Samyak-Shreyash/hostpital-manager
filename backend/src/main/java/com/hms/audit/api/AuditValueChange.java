package com.hms.audit.api;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Old and new value of one audited field (ADR-0016). Only fields on {@link #ALLOWED} may carry values: these are
 * codes (statuses, role and permission codes, flags), never names, free text or PHI. Values are restricted to code
 * characters, so a stray name or sentence cannot be recorded even if a caller passes one; the database re-checks the
 * same character set ({@code V22__audit_changed_values.sql}). List values are sorted, comma-separated codes.
 *
 * @param field    camelCase field name, also listed in the entry's {@code changedFields}
 * @param oldValue value before the change, or {@code null} when there was none
 * @param newValue value after the change, or {@code null} when it was cleared
 */
public record AuditValueChange(String field, String oldValue, String newValue) {

    /** Entity type to the fields whose values may be audited. Extending this needs a review against ADR-0016. */
    public static final Map<String, Set<String>> ALLOWED = Map.of(
            "APP_USER", Set.of("status", "roles"),
            "ROLE", Set.of("permissions"));

    public static final int MAX_VALUE_LENGTH = 2000;

    private static final Pattern VALUE = Pattern.compile("[A-Za-z0-9_:,.-]{0," + MAX_VALUE_LENGTH + "}");

    public AuditValueChange {
        Objects.requireNonNull(field, "field");
        if (!valid(oldValue) || !valid(newValue)) {
            // Never echo the rejected text: it may be the very value that must not be recorded.
            throw new IllegalArgumentException("audited values must be codes only");
        }
    }

    /** Sorted, comma-separated codes for a set-valued field such as roles. */
    public static String codes(java.util.Collection<String> codes) {
        return String.join(",", new java.util.TreeSet<>(codes));
    }

    boolean allowedFor(String entityType) {
        return ALLOWED.getOrDefault(entityType, Set.of()).contains(field);
    }

    private static boolean valid(String value) {
        return value == null || VALUE.matcher(value).matches();
    }
}
