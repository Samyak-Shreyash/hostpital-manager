package com.hms.shared.domain;

import java.util.Map;

/** The request conflicts with current state (409), e.g. {@code SLOT_CONFLICT}, {@code INVOICE_IMMUTABLE}. */
public class ConflictException extends RuntimeException {

    private final String code;
    private final transient Map<String, Object> properties;

    public ConflictException(String code, String message) {
        this(code, message, Map.of());
    }

    /**
     * @param properties extra members of the problem body (e.g. {@code candidates} of {@code DUPLICATE_SUSPECTED});
     *                   documented per code in api-design.md, never free-form values
     */
    public ConflictException(String code, String message, Map<String, Object> properties) {
        super(message);
        this.code = code;
        this.properties = Map.copyOf(properties);
    }

    public String code() {
        return code;
    }

    public Map<String, Object> properties() {
        return properties;
    }
}
