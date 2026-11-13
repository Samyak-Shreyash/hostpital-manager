package com.hms.identity.domain;

import com.hms.identity.api.Permissions;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * The default roles of PRD section 3 ("Personas"), as templates copied into an organization. A wildcard such as
 * {@code user:*} expands to every catalogue code with that prefix. The PRD gives the Facility Administrator
 * {@code report:read}, which is not an API permission; it maps to {@code report:clinical} (operational
 * reports), finance reports staying with the Accountant.
 */
public final class DefaultRoleTemplates {

    public record Template(String code, String name, Set<String> permissions) {
    }

    public static final List<Template> ALL = List.of(
            template("SYSTEM_ADMIN", "System Administrator", "user:*", "role:*", "org:*", Permissions.AUDIT_READ),
            template("FACILITY_ADMIN", "Facility Administrator",
                    "catalog:*", "ward:*", "bed:*", "practitioner:*", Permissions.AUDIT_READ, Permissions.REPORT_CLINICAL),
            template("DOCTOR", "Doctor", Permissions.PATIENT_READ,
                    "encounter:*", "prescription:*", "admission:*", "discharge:*"),
            template("NURSE", "Nurse", Permissions.PATIENT_READ, Permissions.OBSERVATION_WRITE,
                    Permissions.BED_ASSIGN, Permissions.ADMISSION_READ),
            template("RECEPTIONIST", "Receptionist", "patient:*", "appointment:*",
                    Permissions.ADMISSION_CREATE, Permissions.PAYMENT_CREATE),
            template("ACCOUNTANT", "Accountant", "invoice:*", "payment:*", "refund:*", Permissions.REPORT_FINANCE));

    private DefaultRoleTemplates() {
    }

    /** Expands wildcards against the catalogue; an unknown code or an empty wildcard is a programming error. */
    static Set<String> expand(String... patterns) {
        Set<String> codes = new TreeSet<>();
        for (String pattern : patterns) {
            if (pattern.endsWith(":*")) {
                String prefix = pattern.substring(0, pattern.length() - 1);
                List<String> matches = Permissions.ALL.stream().filter(code -> code.startsWith(prefix)).toList();
                if (matches.isEmpty()) {
                    throw new IllegalArgumentException("Wildcard matches no permission: " + pattern);
                }
                codes.addAll(matches);
            } else if (Permissions.ALL.contains(pattern)) {
                codes.add(pattern);
            } else {
                throw new IllegalArgumentException("Unknown permission: " + pattern);
            }
        }
        return Set.copyOf(codes);
    }

    private static Template template(String code, String name, String... patterns) {
        return new Template(code, name, expand(patterns));
    }
}
