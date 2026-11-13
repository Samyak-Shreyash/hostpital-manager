package com.hms.shared.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hms.shared.crypto.FieldCipher.Encrypted;
import com.hms.shared.crypto.FieldCipher.FieldDecryptionException;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** [E3-2] AES-256-GCM field encryption (ADR-0009). Synthetic values only. */
class FieldCipherTest {

    private static final String VALUE = "00000000000001";

    private static byte[] key() {
        byte[] k = new byte[32];
        new SecureRandom().nextBytes(k);
        return k;
    }

    private static Map<String, byte[]> keys(Object... idAndKey) {
        Map<String, byte[]> m = new LinkedHashMap<>();
        for (int i = 0; i < idAndKey.length; i += 2) {
            m.put((String) idAndKey[i], (byte[]) idAndKey[i + 1]);
        }
        return m;
    }

    @Test
    void roundTripsWithTheActiveKeyAndARandomNonce() {
        FieldCipher cipher = new FieldCipher(keys("k1", key()));
        Encrypted a = cipher.encrypt(VALUE);
        Encrypted b = cipher.encrypt(VALUE);
        assertThat(a.keyId()).isEqualTo("k1");
        assertThat(a.value()).hasSize(12 + VALUE.length() + 16);
        assertThat(a.value()).isNotEqualTo(b.value());
        assertThat(new String(a.value(), java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain(VALUE);
        assertThat(cipher.decrypt(a.keyId(), a.value())).isEqualTo(VALUE);
        assertThat(cipher.decrypt(b.keyId(), b.value())).isEqualTo(VALUE);
    }

    @Test
    void rotationEncryptsWithTheFirstKeyAndStillDecryptsOldKeyIds() {
        byte[] oldKey = key();
        Encrypted old = new FieldCipher(keys("k1", oldKey)).encrypt(VALUE);
        FieldCipher rotated = new FieldCipher(keys("k2", key(), "k1", oldKey));
        assertThat(rotated.activeKeyId()).isEqualTo("k2");
        assertThat(rotated.encrypt(VALUE).keyId()).isEqualTo("k2");
        assertThat(rotated.decrypt("k1", old.value())).isEqualTo(VALUE);
    }

    @Test
    void detectsTamperingRelabelledKeyIdsAndUnknownKeys() {
        byte[] k = key();
        FieldCipher cipher = new FieldCipher(keys("k1", k, "k2", k));
        Encrypted e = cipher.encrypt(VALUE);
        for (int i : new int[] {0, 12, e.value().length - 1}) {
            byte[] tampered = e.value().clone();
            tampered[i] ^= 0x01;
            assertThatThrownBy(() -> cipher.decrypt("k1", tampered)).isInstanceOf(FieldDecryptionException.class)
                    .message().doesNotContain(VALUE);
        }
        // same key bytes under another id: the key id is authenticated as associated data
        assertThatThrownBy(() -> cipher.decrypt("k2", e.value())).isInstanceOf(FieldDecryptionException.class);
        assertThatThrownBy(() -> cipher.decrypt("nope", e.value())).isInstanceOf(FieldDecryptionException.class);
        assertThatThrownBy(() -> cipher.decrypt("k1", new byte[10])).isInstanceOf(FieldDecryptionException.class);
    }

    @Test
    void contextIsBoundSoACiphertextCopiedToAnotherRowOrOrganizationFails() {
        FieldCipher cipher = new FieldCipher(keys("k1", key()));
        Encrypted e = cipher.encrypt(VALUE, "patient.abha", "org-a", "patient-1");
        assertThat(cipher.decrypt("k1", e.value(), "patient.abha", "org-a", "patient-1")).isEqualTo(VALUE);
        for (String[] other : new String[][] {{"patient.abha", "org-a", "patient-2"}, {"patient.abha", "org-b",
                "patient-1"}, {"patient.other", "org-a", "patient-1"}, {"patient.abha", "org-a"}, {},
                {"patient.abha", "org-apatient-1", ""}}) {
            assertThatThrownBy(() -> cipher.decrypt("k1", e.value(), other))
                    .isInstanceOf(FieldDecryptionException.class);
        }
    }

    @Test
    void rejectsKeysThatAreNotAes256() {
        assertThatThrownBy(() -> new FieldCipher(keys("k1", new byte[16])))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FieldCipher(Map.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void lookupHashIsDeterministicAndKeyed() {
        byte[] k = key();
        assertThat(new LookupHasher(k).hash(VALUE)).isEqualTo(new LookupHasher(k).hash(VALUE)).hasSize(32);
        assertThat(new LookupHasher(k).hash(VALUE)).isNotEqualTo(new LookupHasher(key()).hash(VALUE));
        assertThat(new LookupHasher(k).hash(VALUE)).isNotEqualTo(new LookupHasher(k).hash("00000000000002"));
        assertThatThrownBy(() -> new LookupHasher(new byte[16])).isInstanceOf(IllegalArgumentException.class);
    }
}
