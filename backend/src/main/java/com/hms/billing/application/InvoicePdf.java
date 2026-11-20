package com.hms.billing.application;

import com.hms.shared.pdf.PdfDocumentBuilder;
import com.hms.shared.pdf.PdfDocumentBuilder.KeyValue;
import com.hms.shared.pdf.PdfDocumentBuilder.Options;
import java.util.ArrayList;
import java.util.List;

/**
 * Lays out an issued invoice as a PDF ([E7-5]). Pure rendering: the caller supplies already-resolved business data
 * and money as decimal strings (the same strings the API returns), so no internal id can reach the document and the
 * printed totals cannot drift from the JSON.
 */
public final class InvoicePdf {

    /** One printed line. */
    public record Line(String description, String quantity, String unitPrice, String discount, String tax,
            String total) {
    }

    /** Everything printed; {@code cancelled} adds the watermark. */
    public record Content(String organizationName, String organizationAddress, String invoiceNo, String date,
            String patientName, String uhid, String status, boolean cancelled, List<Line> lines, String subtotal,
            String discountTotal, String taxTotal, String total, String credited, String paid, String refunded,
            String outstanding) {
    }

    private InvoicePdf() {
    }

    public static byte[] render(Content c) {
        PdfDocumentBuilder pdf = new PdfDocumentBuilder("Invoice",
                new Options(c.cancelled() ? "CANCELLED" : null, "Computer-generated invoice"));
        pdf.header(c.organizationName(), c.organizationAddress())
                .heading("Invoice")
                .keyValues(List.of(new KeyValue("Invoice no.", c.invoiceNo()), new KeyValue("Date", c.date()),
                        new KeyValue("Patient", c.patientName()), new KeyValue("UHID", c.uhid()),
                        new KeyValue("Status", c.status())));
        List<List<String>> rows = new ArrayList<>();
        int n = 1;
        for (Line l : c.lines()) {
            rows.add(List.of(String.valueOf(n++), l.description(), l.quantity(), l.unitPrice(), l.discount(),
                    l.tax(), l.total()));
        }
        pdf.table(List.of("#", "Description", "Qty", "Unit price", "Discount", "Tax", "Total"), rows,
                new float[] {0.5f, 3.5f, 0.8f, 1.4f, 1.2f, 1.2f, 1.4f});
        pdf.heading("Summary")
                .table(List.of("Item", "Amount"),
                        List.of(List.of("Subtotal", c.subtotal()), List.of("Discount", c.discountTotal()),
                                List.of("Tax", c.taxTotal()), List.of("Total", c.total()),
                                List.of("Credit notes", c.credited()), List.of("Paid", c.paid()),
                                List.of("Refunded", c.refunded()), List.of("Outstanding", c.outstanding())),
                        new float[] {3f, 1.5f});
        return pdf.build();
    }
}
