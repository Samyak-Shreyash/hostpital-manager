package com.hms.shared.crypto;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/**
 * Builds the field cipher and the ABHA lookup hasher at startup and fails fast on missing or malformed keys
 * (ADR-0009). Built-in default keys apply only under the {@code dev} or {@code test} profile. Error messages never
 * contain key material.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(FieldEncryptionProperties.class)
public class FieldEncryptionConfig {

    private static final Logger log = LoggerFactory.getLogger(FieldEncryptionConfig.class);

    static final Profiles DEFAULT_KEY_PROFILES = Profiles.of("dev", "test");

    @Bean
    FieldCipher fieldCipher(FieldEncryptionProperties props, Environment env) {
        return new FieldCipher(
                parseKeys(resolve(props.keys(), props.devDefaultKeys(), env, "HMS_FIELD_ENCRYPTION_KEYS")));
    }

    @Bean
    LookupHasher abhaLookupHasher(FieldEncryptionProperties props, Environment env) {
        String key = resolve(props.abhaHmacKey(), props.devDefaultAbhaHmacKey(), env, "HMS_ABHA_HMAC_KEY");
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(key.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("HMS_ABHA_HMAC_KEY is not valid base64.");
        }
        if (bytes.length < LookupHasher.MIN_KEY_BYTES) {
            throw new IllegalStateException("HMS_ABHA_HMAC_KEY must be at least 32 bytes.");
        }
        return new LookupHasher(bytes);
    }

    static String resolve(String configured, String devDefault, Environment env, String variable) {
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        if (env.acceptsProfiles(DEFAULT_KEY_PROFILES) && devDefault != null && !devDefault.isBlank()) {
            log.warn("{} is not set: using the PUBLIC built-in {} key of the dev/test profile. Data encrypted with it is "
                    + "not protected; never use this outside local development.", variable, variable);
            return devDefault;
        }
        throw new IllegalStateException(variable + " is required (ADR-0009); built-in keys exist only for the dev "
                + "and test profiles.");
    }

    /** {@code keyId:base64key[,keyId:base64key...]}, the first active. */
    static Map<String, byte[]> parseKeys(String spec) {
        Map<String, byte[]> keys = new LinkedHashMap<>();
        for (String pair : spec.split(",")) {
            String trimmed = pair.trim();
            int colon = trimmed.indexOf(':');
            if (colon <= 0 || colon == trimmed.length() - 1) {
                throw new IllegalStateException("HMS_FIELD_ENCRYPTION_KEYS entries must be keyId:base64key.");
            }
            String id = trimmed.substring(0, colon).trim();
            if (!id.matches("[A-Za-z0-9_-]{1,32}")) {
                throw new IllegalStateException("HMS_FIELD_ENCRYPTION_KEYS key ids must be 1-32 of [A-Za-z0-9_-].");
            }
            byte[] key;
            try {
                key = Base64.getDecoder().decode(trimmed.substring(colon + 1).trim());
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("HMS_FIELD_ENCRYPTION_KEYS key '" + id + "' is not valid base64.");
            }
            if (key.length != FieldCipher.KEY_BYTES) {
                throw new IllegalStateException("HMS_FIELD_ENCRYPTION_KEYS key '" + id + "' must be 32 bytes.");
            }
            if (keys.putIfAbsent(id, key) != null) {
                throw new IllegalStateException("HMS_FIELD_ENCRYPTION_KEYS has duplicate key id '" + id + "'.");
            }
        }
        return keys;
    }
}
