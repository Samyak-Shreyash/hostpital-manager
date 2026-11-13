package com.hms.patient.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.hms.patient.application.RegisterPatientCommand;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** [E3-2]/[E3-3] Records carrying an ABHA number, an override reason or duplicate-check terms never print them. */
class AbhaRedactionTest {

    private static final String ABHA = "00123456789012";
    private static final String REASON = "Synthetic twin sibling reason";

    @Test
    void toStringNeverContainsTheAbhaNumber() {
        CreatePatientRequest request = new CreatePatientRequest("Test Person", "F", null, 30, true, "+910000000001",
                null, null, ABHA, true, REASON);
        RegisterPatientCommand command = request.toCommand();
        SearchByAbhaRequest search = new SearchByAbhaRequest(ABHA);
        for (Object o : new Object[] {request, command, search}) {
            assertThat(o.toString()).doesNotContain(ABHA).doesNotContain("9012").contains("<redacted>");
        }
        assertThat(command.abhaNumber()).isEqualTo(ABHA);
        assertThat(new SearchByAbhaRequest(null).toString()).contains("abhaNumber=null");
    }

    @Test
    void toStringNeverContainsTheOverrideReasonOrCheckTerms() {
        CreatePatientRequest request = new CreatePatientRequest("Test Person", "F", null, 30, true, "+910000000001",
                null, null, null, true, REASON);
        assertThat(request.toString()).doesNotContain(REASON).doesNotContain("Test Person");
        assertThat(request.toCommand().toString()).doesNotContain(REASON).doesNotContain("Test Person");
        assertThat(request.toCommand().overrideReason()).isEqualTo(REASON);
        DuplicateCheckRequest check = new DuplicateCheckRequest("Test Person", LocalDate.of(1988, 4, 12),
                "+910000000001");
        assertThat(check.toString()).doesNotContain("Test Person").doesNotContain("0000000001");
    }
}
