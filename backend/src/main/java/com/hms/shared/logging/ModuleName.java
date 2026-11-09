package com.hms.shared.logging;

/** The owning module of a class or logger (ADR-0014): the package segment after {@code com.hms}. */
public final class ModuleName {

    private static final String ROOT = "com.hms.";

    private ModuleName() {
    }

    /** {@code com.hms.billing.application.X} → {@code billing}; {@code null} for non-HMS names. */
    public static String of(String className) {
        if (className == null || !className.startsWith(ROOT)) {
            return null;
        }
        int end = className.indexOf('.', ROOT.length());
        return end < 0 ? null : className.substring(ROOT.length(), end);
    }
}
