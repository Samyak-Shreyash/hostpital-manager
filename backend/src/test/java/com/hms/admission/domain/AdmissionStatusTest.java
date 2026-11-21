package com.hms.admission.domain;

import static com.hms.admission.domain.AdmissionStatus.ADMITTED;
import static com.hms.admission.domain.AdmissionStatus.CANCELLED;
import static com.hms.admission.domain.AdmissionStatus.DISCHARGED;
import static com.hms.admission.domain.AdmissionStatus.DISCHARGE_INITIATED;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AdmissionStatusTest {

    private static final Map<AdmissionStatus, Set<AdmissionStatus>> ALLOWED = Map.of(
            ADMITTED, Set.of(DISCHARGE_INITIATED, CANCELLED),
            DISCHARGE_INITIATED, Set.of(ADMITTED, DISCHARGED),
            DISCHARGED, Set.of(),
            CANCELLED, Set.of());

    @Test
    void onlyTheDocumentedTransitionsAreAllowed() {
        for (AdmissionStatus from : AdmissionStatus.values()) {
            for (AdmissionStatus to : AdmissionStatus.values()) {
                assertThat(from.canTransitionTo(to)).as(from + " -> " + to)
                        .isEqualTo(ALLOWED.get(from).contains(to));
            }
        }
    }

    @Test
    void activeMeansAdmittedOrDischargeInitiated() {
        assertThat(ADMITTED.isActive()).isTrue();
        assertThat(DISCHARGE_INITIATED.isActive()).isTrue();
        assertThat(DISCHARGED.isActive()).isFalse();
        assertThat(CANCELLED.isActive()).isFalse();
    }
}
