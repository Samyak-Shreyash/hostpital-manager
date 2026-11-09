package com.hms.shared.domain;

/** A business rule rejected a well-formed request (422, {@code BUSINESS_RULE_VIOLATION} unless a code is given). */
public class BusinessRuleException extends RuntimeException {

    private final String code;

    public BusinessRuleException(String message) {
        this("BUSINESS_RULE_VIOLATION", message);
    }

    public BusinessRuleException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
