package com.hms.clinical;

import static org.assertj.core.api.Assertions.assertThat;

import com.hms.clinical.application.PrescriptionPdf;
import com.hms.clinical.application.PrescriptionPdf.Content;
import com.hms.clinical.application.PrescriptionPdf.Line;
import com.hms.support.PdfText;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** [E6-5] Snapshot-by-text (plan R7): the extracted text carries the fixture lines; bytes are not compared. */
class PrescriptionPdfTest {

    private static final Pattern UUID_PATTERN =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private static Content content(boolean superseded) {
        return new Content("Synthetic Care Clinic", "1 Test Road, Sampletown 560001", "Asha Testpatient",
                "HMS-000042", "Dr Rohan Sampledoctor", "KMC-12345", "20 Nov 2026", superseded,
                List.of(new Line("Paracetamol", "500 mg", "Tablet", "Oral", "1", "TID", 3, "After food"),
                        new Line("Ibuprofen", null, null, null, "2", "BD", 5, null)));
    }

    @Test
    void issuedPdfCarriesFixtureLinesAndNoUuid() throws Exception {
        byte[] pdf = PrescriptionPdf.render(content(false));
        assertThat(new String(pdf, 0, 5, java.nio.charset.StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
        String text = PdfText.of(pdf);
        assertThat(text).contains("Synthetic Care Clinic", "1 Test Road, Sampletown 560001", "Prescription",
                "Asha Testpatient", "HMS-000042", "Dr Rohan Sampledoctor", "KMC-12345", "20 Nov 2026",
                "Paracetamol", "500 mg", "Tablet", "Oral", "TID", "After food", "Ibuprofen", "BD");
        assertThat(text).doesNotContain("SUPERSEDED");
        assertThat(UUID_PATTERN.matcher(text).find()).isFalse();
    }

    @Test
    void supersededPdfShowsWatermarkText() throws Exception {
        assertThat(PdfText.of(PrescriptionPdf.render(content(true)))).contains("SUPERSEDED", "Paracetamol");
    }
}
