package com.hms.patient.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.hms.patient.application.RegisterPatientCommand;
import org.junit.jupiter.api.Test;

/** [E3-2] Records carrying an ABHA number never print it. Synthetic values only. */
class AbhaRedactionTest {

    private static final String ABHA = "00123456789012";

    @Test
    void toStringNeverContainsTheAbhaNumber() {
        CreatePatientRequest request = new CreatePatientRequest("Test Person", "F", null, 30, true, "+910000000001",
                null, null, ABHA);
        RegisterPatientCommand command = request.toCommand();
        SearchByAbhaRequest search = new SearchByAbhaRequest(ABHA);
        for (Object o : new Object[] {request, command, search}) {
            assertThat(o.toString()).doesNotContain(ABHA).doesNotContain("9012").contains("<redacted>");
        }
        assertThat(command.abhaNumber()).isEqualTo(ABHA);
        assertThat(new SearchByAbhaRequest(null).toString()).contains("abhaNumber=null");
    }
}
