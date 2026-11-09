package com.hms.shared.domain;

/** The resource does not exist <em>or belongs to another organization</em>; both answer 404. */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
