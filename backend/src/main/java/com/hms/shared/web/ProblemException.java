package com.hms.shared.web;

import org.springframework.http.HttpStatus;

/** An error whose status and stable {@code code} are fixed by docs/api/api-design.md, e.g. 423 ACCOUNT_LOCKED. */
public class ProblemException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ProblemException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
