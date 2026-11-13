package com.hms.shared.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Keyed lookup hash (HMAC-SHA256) for equality search over an encrypted field (ADR-0009). Uses its own key, separate
 * from the encryption keys. Callers normalize the value before hashing.
 */
public final class LookupHasher {

    static final int MIN_KEY_BYTES = 32;
    private static final String ALGORITHM = "HmacSHA256";

    private final SecretKeySpec key;

    public LookupHasher(byte[] key) {
        if (key.length < MIN_KEY_BYTES) {
            throw new IllegalArgumentException("Lookup-hash key must be at least 32 bytes.");
        }
        this.key = new SecretKeySpec(key, ALGORITHM);
    }

    public byte[] hash(String value) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Lookup hash failed.", e);
        }
    }
}
