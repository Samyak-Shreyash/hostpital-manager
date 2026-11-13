package com.hms.shared.crypto;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code hms.field-encryption.*}. {@code keys} ({@code HMS_FIELD_ENCRYPTION_KEYS}) is comma-separated
 * {@code keyId:base64key} pairs, the first active; {@code abhaHmacKey} ({@code HMS_ABHA_HMAC_KEY}) is base64. The
 * {@code devDefault*} values are set only by application-dev.yml / application-test.yml and are honoured only while
 * the dev or test profile is active.
 */
@ConfigurationProperties("hms.field-encryption")
public record FieldEncryptionProperties(String keys, String abhaHmacKey, String devDefaultKeys,
        String devDefaultAbhaHmacKey) {
}
