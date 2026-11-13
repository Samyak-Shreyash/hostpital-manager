package com.hms.organization.api;

import java.time.ZoneId;
import java.util.Set;
import java.util.regex.Pattern;

/** Public validation rules for organization settings (api-design.md section 4). */
public final class OrganizationRules {

    public static final String GSTIN_REGEX = "[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]";
    public static final String DEFAULT_TIMEZONE = "Asia/Kolkata";

    private static final Set<String> ZONE_IDS = ZoneId.getAvailableZoneIds();
    private static final Pattern GSTIN = Pattern.compile(GSTIN_REGEX);

    private OrganizationRules() {
    }

    /** A region-based IANA id such as {@code Asia/Kolkata} or {@code UTC}; offsets like {@code +05:30} are not. */
    public static boolean isIanaTimezone(String value) {
        return value != null && ZONE_IDS.contains(value) && !value.startsWith("SystemV/");
    }

    public static boolean isGstin(String value) {
        return value != null && GSTIN.matcher(value).matches();
    }
}
