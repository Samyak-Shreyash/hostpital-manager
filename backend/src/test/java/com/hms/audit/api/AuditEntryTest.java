package com.hms.audit.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** [E1-1] AuditEntry accepts identifiers and field names only (PRD AUD-2, AUD-4). Synthetic data only. */
class AuditEntryTest {

    private static final UUID ORG = UUID.randomUUID();

    @Test
    void changedFieldsAreDeduplicatedAndSorted() {
        assertThat(entry(List.of("phone", "address.city", "phone")).changedFields())
                .containsExactly("address.city", "phone");
    }

    @Test
    void entryWithoutChangedFieldsIsEmpty() {
        assertThat(AuditEntry.of(ORG, null, "LOGIN_FAILED", "USER", null, AuditOutcome.FAILURE).changedFields())
                .isEmpty();
        assertThat(entry(null).changedFields()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"9000000001", "test.user@example.invalid", "Fever since three days", "Synthetic Name",
            "", "Phone", "pass word", "address.", "a,b"})
    void valuesInChangedFieldsAreRejectedWithoutEchoingThem(String value) {
        assertThatThrownBy(() -> entry(List.of("phone", value)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("changedFields must contain field names only");
    }

    @Test
    void nullChangedFieldIsRejected() {
        assertThatThrownBy(() -> entry(Arrays.asList("phone", null))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void atMostFiftyChangedFields() {
        assertThat(entry(fields(50)).changedFields()).hasSize(50);
        assertThatThrownBy(() -> entry(fields(51))).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"patient_viewed", "X", "PATIENT VIEWED", "9_VIEWED"})
    void actionAndEntityTypeMustBeUpperSnakeCase(String code) {
        assertThatThrownBy(() -> AuditEntry.of(ORG, null, code, "PATIENT", null, AuditOutcome.SUCCESS))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AuditEntry.of(ORG, null, "PATIENT_VIEWED", code, null, AuditOutcome.SUCCESS))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullActionOrEntityTypeIsRejected() {
        assertThatThrownBy(() -> AuditEntry.of(ORG, null, null, "PATIENT", null, AuditOutcome.SUCCESS))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AuditEntry.of(ORG, null, "PATIENT_VIEWED", null, null, AuditOutcome.SUCCESS))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void organizationAndOutcomeAreRequiredButActorAndEntityAreOptional() {
        assertThatNullPointerException()
                .isThrownBy(() -> AuditEntry.of(null, null, "LOGIN_FAILED", "USER", null, AuditOutcome.FAILURE));
        assertThatNullPointerException()
                .isThrownBy(() -> AuditEntry.of(ORG, null, "LOGIN_FAILED", "USER", null, null));
        AuditEntry system = AuditEntry.of(ORG, null, "LOGIN_FAILED", "USER", null, AuditOutcome.FAILURE);
        assertThat(system.actorId()).isNull();
        assertThat(system.entityId()).isNull();
    }

    private static AuditEntry entry(List<String> changedFields) {
        return new AuditEntry(ORG, UUID.randomUUID(), "PATIENT_UPDATED", "PATIENT", UUID.randomUUID(),
                AuditOutcome.SUCCESS, changedFields);
    }

    private static List<String> fields(int count) {
        return IntStream.range(0, count).mapToObj(i -> "field" + i).toList();
    }
}
