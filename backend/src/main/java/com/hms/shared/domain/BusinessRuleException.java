package com.hms.shared.domain;

/** A business rule rejected a well-formed request (422, {@code BUSINESS_RULE_VIOLATION} unless a code is given). */
public class BusinessRuleException extends RuntimeException {

    private final String code;
    private final transient java.util.Map<String, Object> properties;

    public BusinessRuleException(String message) {
        this("BUSINESS_RULE_VIOLATION", message);
    }

    public BusinessRuleException(String code, String message) {
        this(code, message, java.util.Map.of());
    }

    /**
     * @param properties extra members of the problem body (e.g. {@code rows} of {@code IMPORT_REJECTED});
     *                   documented per code in api-design.md, never free-form values
     */
    public BusinessRuleException(String code, String message, java.util.Map<String, Object> properties) {
        super(message);
        this.code = code;
        this.properties = java.util.Map.copyOf(properties);
    }

    public java.util.Map<String, Object> properties() {
        return properties;
    }

    public String code() {
        return code;
    }
}
