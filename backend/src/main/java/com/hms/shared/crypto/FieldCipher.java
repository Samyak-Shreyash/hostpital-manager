package com.hms.shared.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Application-level field encryption (ADR-0009): AES-256-GCM, a random 96-bit nonce per value, 128-bit tag. The key
 * id is stored with each row so keys can rotate: the first key encrypts, every configured key decrypts. The key id is
 * bound as associated data, so a ciphertext relabelled with another key id fails authentication.
 * Stored form: {@code nonce (12 bytes) || ciphertext+tag}. Never log plaintexts or keys.
 */
public final class FieldCipher {

    static final int KEY_BYTES = 32;
    static final int NONCE_BYTES = 12;
    static final int TAG_BITS = 128;
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    /** An encrypted value and the id of the key that encrypted it. */
    public record Encrypted(String keyId, byte[] value) {
    }

    private final String activeKeyId;
    private final Map<String, SecretKey> keys;
    private final SecureRandom random = new SecureRandom();

    /** @param keys key id to 32-byte key, in order; the first entry is the active key */
    public FieldCipher(Map<String, byte[]> keys) {
        if (keys.isEmpty()) {
            throw new IllegalArgumentException("At least one field-encryption key is required.");
        }
        Map<String, SecretKey> copy = new LinkedHashMap<>();
        keys.forEach((id, key) -> {
            if (key.length != KEY_BYTES) {
                throw new IllegalArgumentException("Field-encryption key '" + id + "' must be 32 bytes (AES-256).");
            }
            copy.put(id, new SecretKeySpec(key, "AES"));
        });
        this.activeKeyId = copy.keySet().iterator().next();
        this.keys = Map.copyOf(copy);
    }

    public String activeKeyId() {
        return activeKeyId;
    }

    public Encrypted encrypt(String plaintext) {
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, keys.get(activeKeyId), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(activeKeyId.getBytes(StandardCharsets.UTF_8));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = Arrays.copyOf(nonce, NONCE_BYTES + ct.length);
            System.arraycopy(ct, 0, out, NONCE_BYTES, ct.length);
            return new Encrypted(activeKeyId, out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Field encryption failed.", e);
        }
    }

    /** @throws FieldDecryptionException for an unknown key id or a value that fails authentication (tampered) */
    public String decrypt(String keyId, byte[] value) {
        SecretKey key = keyId == null ? null : keys.get(keyId);
        if (key == null) {
            throw new FieldDecryptionException("Unknown field-encryption key id.");
        }
        if (value == null || value.length < NONCE_BYTES + TAG_BITS / 8) {
            throw new FieldDecryptionException("Encrypted value is too short.");
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, value, 0, NONCE_BYTES));
            cipher.updateAAD(keyId.getBytes(StandardCharsets.UTF_8));
            byte[] plain = cipher.doFinal(value, NONCE_BYTES, value.length - NONCE_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (AEADBadTagException e) {
            throw new FieldDecryptionException("Encrypted value failed authentication.");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Field decryption failed.", e);
        }
    }

    /** Decryption refused; the message never contains the value. */
    public static final class FieldDecryptionException extends RuntimeException {
        FieldDecryptionException(String message) {
            super(message);
        }
    }
}
