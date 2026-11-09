package com.hms.shared.domain;

/** The request conflicts with current state (409), e.g. {@code SLOT_CONFLICT}, {@code INVOICE_IMMUTABLE}. */
public class ConflictException extends RuntimeException {

    private final String code;

    public ConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
