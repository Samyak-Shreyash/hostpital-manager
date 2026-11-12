package com.hms.identity.domain;

import java.security.SecureRandom;

/**
 * Generates the temporary password an administrator hands to a user (create and reset). 16 characters from an
 * alphabet without look-alikes (no 0/O, 1/l/I), so it can be read out or typed from paper; about 92 bits of
 * entropy. It always satisfies {@link PasswordPolicy}, and the user must change it at first login.
 */
public final class TemporaryPassword {

    static final int LENGTH = 16;
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private TemporaryPassword() {
    }

    public static String generate() {
        StringBuilder password = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            password.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return password.toString();
    }
}
