package com.hms.shared.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** [E3-2] Fail-fast key configuration (ADR-0009): built-in keys only under the dev/test profiles. */
@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
class FieldEncryptionConfigTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String DEV_DEFAULTS_KEYS = "hms.field-encryption.dev-default-keys=dev-1:" + KEY;
    private static final String DEV_DEFAULTS_HMAC = "hms.field-encryption.dev-default-abha-hmac-key=" + KEY;

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(FieldEncryptionConfig.class);

    @Test
    void failsStartupWithoutKeysOutsideDevAndTest() {
        runner.run(ctx -> assertThat(ctx).hasFailed().getFailure().hasStackTraceContaining(
                "HMS_FIELD_ENCRYPTION_KEYS is required"));
        // dev defaults present but a production profile: still refused
        runner.withPropertyValues(DEV_DEFAULTS_KEYS, DEV_DEFAULTS_HMAC, "spring.profiles.active=prod")
                .run(ctx -> assertThat(ctx).hasFailed());
        runner.withPropertyValues("hms.field-encryption.keys=k1:" + KEY, "spring.profiles.active=prod")
                .run(ctx -> assertThat(ctx).hasFailed().getFailure().hasStackTraceContaining(
                        "HMS_ABHA_HMAC_KEY is required"));
    }

    @Test
    void warnsWhenBuiltInKeysAreUsed(org.springframework.boot.test.system.CapturedOutput output) {
        runner.withPropertyValues(DEV_DEFAULTS_KEYS, DEV_DEFAULTS_HMAC, "spring.profiles.active=dev")
                .run(ctx -> assertThat(ctx).hasNotFailed());
        assertThat(output.getAll()).contains("HMS_FIELD_ENCRYPTION_KEYS is not set").contains(
                "HMS_ABHA_HMAC_KEY is not set").doesNotContain(KEY);
    }

    @Test
    void usesBuiltInKeysUnderDevOrTestProfile() {
        for (String profile : new String[] {"dev", "test"}) {
            runner.withPropertyValues(DEV_DEFAULTS_KEYS, DEV_DEFAULTS_HMAC, "spring.profiles.active=" + profile)
                    .run(ctx -> {
                        assertThat(ctx).hasNotFailed();
                        assertThat(ctx.getBean(FieldCipher.class).activeKeyId()).isEqualTo("dev-1");
                    });
        }
    }

    @Test
    void explicitKeysWinAndMalformedKeysFailWithoutLeakingThem() {
        runner.withPropertyValues("hms.field-encryption.keys=k2:" + KEY + ", k1:" + KEY,
                "hms.field-encryption.abha-hmac-key=" + KEY).run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx.getBean(FieldCipher.class).activeKeyId()).isEqualTo("k2");
                });
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);
        for (String keys : new String[] {"k1:" + shortKey, "k1", ":" + KEY, "k1:not*base64", "k1:" + KEY + ",k1:" + KEY,
                "bad id:" + KEY}) {
            runner.withPropertyValues("hms.field-encryption.keys=" + keys, "hms.field-encryption.abha-hmac-key=" + KEY)
                    .run(ctx -> assertThat(ctx).hasFailed().getFailure().hasStackTraceContaining(
                            "HMS_FIELD_ENCRYPTION_KEYS").satisfies(f -> assertThat(stack(f)).doesNotContain(KEY)));
        }
        runner.withPropertyValues("hms.field-encryption.keys=k1:" + KEY, "hms.field-encryption.abha-hmac-key=" + shortKey)
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    private static String stack(Throwable t) {
        java.io.StringWriter w = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(w));
        return w.toString();
    }
}
