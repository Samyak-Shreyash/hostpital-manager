package com.hms.shared.logging;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.boot.json.JsonWriter.MemberPath;
import org.springframework.boot.json.JsonWriter.ValueProcessor;

/**
 * Defence-in-depth redaction for JSON log lines. The rule is still "never log PHI, passwords or tokens;
 * log ids, not names" (CLAUDE.md); this catches mistakes:
 * <ul>
 *   <li>values of sensitive keys (MDC or key-value pairs such as {@code password}, {@code phone}) are replaced;</li>
 *   <li>in every other text value, email addresses, ABHA numbers, Aadhaar-like numbers and Indian mobile
 *       numbers are masked.</li>
 * </ul>
 * Fields the platform itself controls (timestamps, ids, logger) are left untouched.
 */
public final class PhiRedactor {

    static final String REDACTED = "[REDACTED]";

    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "password", "passwd", "secret", "token", "accesstoken", "refreshtoken", "authorization", "cookie",
            "otp", "pin", "name", "fullname", "firstname", "middlename", "lastname", "patientname", "phone",
            "mobile", "phonenumber", "email", "address", "dob", "dateofbirth", "abha", "abhanumber", "abhaaddress",
            "aadhaar", "diagnosis", "notes", "complaint");

    private static final Set<String> TRUSTED_KEYS = Set.of(
            "@timestamp", "@version", "level", "level_value", "logger_name", "thread_name", "module",
            "correlationId", "userId", "orgId");

    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    // Digit patterns must not touch letters, digits or hyphens on either side, so UUID groups are left alone.
    private static final Pattern ABHA = Pattern.compile("(?<![\\w-])\\d{2}-?\\d{4}-?\\d{4}-?\\d{4}(?![\\w-])");
    private static final Pattern AADHAAR = Pattern.compile("(?<![\\w-])\\d{4}[ -]?\\d{4}[ -]?\\d{4}(?![\\w-])");
    private static final Pattern MOBILE = Pattern.compile("(?<![\\w+-])(?:\\+?91[ -]?|0)?[6-9]\\d{9}(?![\\w-])");

    /** Value processor for Spring Boot's structured JSON logging. */
    public static final ValueProcessor<Object> VALUE_PROCESSOR = PhiRedactor::process;

    private PhiRedactor() {
    }

    static Object process(MemberPath path, Object value) {
        String key = path.name();
        if (value == null || key == null || TRUSTED_KEYS.contains(key)) {
            return value;
        }
        if (isSensitiveKey(key)) {
            return REDACTED;
        }
        return value instanceof CharSequence text ? mask(text.toString()) : value;
    }

    static boolean isSensitiveKey(String key) {
        return SENSITIVE_KEYS.contains(key.replaceAll("[_.\\-]", "").toLowerCase(Locale.ROOT));
    }

    /** Masks contact and identity numbers in free text. */
    public static String mask(String text) {
        String masked = EMAIL.matcher(text).replaceAll("[EMAIL]");
        masked = ABHA.matcher(masked).replaceAll("[ABHA]");
        masked = AADHAAR.matcher(masked).replaceAll("[AADHAAR]");
        return MOBILE.matcher(masked).replaceAll("[PHONE]");
    }
}
