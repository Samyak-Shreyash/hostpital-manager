package com.hms.support;

import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;

/** Text extraction for PDF assertions (plan R7), shared by the unit and integration suites. */
public final class PdfText {

    private PdfText() {
    }

    /** The text of every page, joined by a newline; bytes are never compared. */
    public static String of(byte[] pdf) throws Exception {
        PdfReader reader = new PdfReader(pdf);
        try {
            PdfTextExtractor extractor = new PdfTextExtractor(reader);
            StringBuilder all = new StringBuilder();
            for (int page = 1; page <= reader.getNumberOfPages(); page++) {
                all.append(extractor.getTextFromPage(page)).append('\n');
            }
            return all.toString();
        } finally {
            reader.close();
        }
    }
}
