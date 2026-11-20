package com.hms.clinical.application;

import com.hms.shared.pdf.PdfDocumentBuilder;
import com.hms.shared.pdf.PdfDocumentBuilder.KeyValue;
import com.hms.shared.pdf.PdfDocumentBuilder.Options;
import java.util.ArrayList;
import java.util.List;

/**
 * Lays out a prescription as a PDF ([E6-5]). Pure rendering: the caller supplies already-resolved business data
 * (names, UHID, registration number), so no internal id can reach the document.
 */
public final class PrescriptionPdf {

    /** One prescribed line. */
    public record Line(String drugName, String strength, String form, String route, String dose, String frequency,
            int durationDays, String instructions) {
    }

    /** Everything printed; {@code superseded} adds the watermark. */
    public record Content(String organizationName, String organizationAddress, String patientName, String uhid,
            String doctorName, String registrationNo, String date, boolean superseded, List<Line> lines) {
    }

    private PrescriptionPdf() {
    }

    public static byte[] render(Content c) {
        PdfDocumentBuilder pdf = new PdfDocumentBuilder("Prescription",
                new Options(c.superseded() ? "SUPERSEDED" : null, "Computer-generated prescription"));
        pdf.header(c.organizationName(), c.organizationAddress())
                .heading("Prescription")
                .keyValues(List.of(new KeyValue("Patient", c.patientName()), new KeyValue("UHID", c.uhid()),
                        new KeyValue("Doctor", c.doctorName()), new KeyValue("Reg. no.", c.registrationNo()),
                        new KeyValue("Date", c.date()),
                        new KeyValue("Status", c.superseded() ? "Superseded" : "Issued")));
        List<List<String>> rows = new ArrayList<>();
        int n = 1;
        for (Line l : c.lines()) {
            rows.add(List.of(String.valueOf(n++), drug(l), orEmpty(l.dose()) + " " + orEmpty(l.frequency()),
                    l.durationDays() + (l.durationDays() == 1 ? " day" : " days"), orEmpty(l.instructions())));
        }
        pdf.table(List.of("#", "Medicine", "Dose / frequency", "Duration", "Instructions"), rows,
                new float[] {0.5f, 3f, 2f, 1.2f, 3f});
        return pdf.build();
    }

    private static String drug(Line l) {
        List<String> parts = new ArrayList<>();
        for (String p : new String[] {l.drugName(), l.strength(), l.form(), l.route()}) {
            if (p != null && !p.isBlank()) {
                parts.add(p);
            }
        }
        return String.join(" ", parts);
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }
}
