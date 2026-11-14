package com.hms.patient.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.hms.patient.application.ClinicalEntryViews.AllergyView;
import com.hms.patient.application.ClinicalEntryViews.HistoryView;
import com.hms.patient.infrastructure.ClinicalEntryRepository.EntryRow;
import com.hms.patient.web.ClinicalEntryRequests.CreateAllergyRequest;
import com.hms.patient.web.ClinicalEntryRequests.CreateHistoryRequest;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** [E3-4] Records carrying allergy or history free text never print it. */
class ClinicalEntryRedactionTest {

    @Test
    void toStringNeverContainsClinicalFreeText() {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        Object[] all = {
            new CreateAllergyRequest("Syntheticillin", "Synthetic rash", "MILD"),
            new CreateHistoryRequest("Syntheticosis", "Synthetic notes"),
            new AllergyView(id, "Syntheticillin", "Synthetic rash", "MILD", "ACTIVE", now, null),
            new HistoryView(id, "Syntheticosis", "Synthetic notes", "ACTIVE", now, null),
            new EntryRow(id, "ALLERGY", "Syntheticillin", "Synthetic rash", "MILD", "ACTIVE", now, null)};
        for (Object o : all) {
            assertThat(o.toString()).doesNotContain("Synthetic");
        }
    }
}
