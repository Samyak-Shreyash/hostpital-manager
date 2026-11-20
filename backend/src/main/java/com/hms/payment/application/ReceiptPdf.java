package com.hms.payment.application;

import com.hms.shared.pdf.PdfDocumentBuilder;
import com.hms.shared.pdf.PdfDocumentBuilder.KeyValue;
import com.hms.shared.pdf.PdfDocumentBuilder.Options;
import java.util.List;

/**
 * Lays out a payment receipt as a PDF ([E7-5]). Pure rendering of already-resolved business data (receipt and
 * invoice numbers, patient name and UHID); no internal id can reach the document. {@code referenceNo} may be null.
 */
public final class ReceiptPdf {

    /** Everything printed. */
    public record Content(String organizationName, String organizationAddress, String receiptNo, String invoiceNo,
            String date, String patientName, String uhid, String amount, String mode, String referenceNo) {
    }

    private ReceiptPdf() {
    }

    public static byte[] render(Content c) {
        return new PdfDocumentBuilder("Receipt", new Options(null, "Computer-generated receipt"))
                .header(c.organizationName(), c.organizationAddress())
                .heading("Payment receipt")
                .keyValues(List.of(new KeyValue("Receipt no.", c.receiptNo()),
                        new KeyValue("Invoice no.", c.invoiceNo()), new KeyValue("Date", c.date()),
                        new KeyValue("Patient", c.patientName()), new KeyValue("UHID", c.uhid()),
                        new KeyValue("Amount", c.amount()), new KeyValue("Mode", c.mode()),
                        new KeyValue("Reference", c.referenceNo())))
                .build();
    }
}
