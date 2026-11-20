package com.hms.shared.pdf;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.ColumnText;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfPageEventHelper;
import com.lowagie.text.pdf.PdfWriter;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.util.List;

/**
 * A small A4 document helper (ADR-0017): header with organization name and address, headings, a key-value block,
 * a table, an optional diagonal watermark and a footer with page numbers. Fonts are the built-in Helvetica family
 * (Latin text only). Callers pass already-formatted strings; never put internal ids or secrets into a document.
 */
public final class PdfDocumentBuilder {

    private static final Font TITLE = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16);
    private static final Font ADDRESS = FontFactory.getFont(FontFactory.HELVETICA, 9);
    private static final Font HEADING = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12);
    private static final Font LABEL = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10);
    private static final Font BODY = FontFactory.getFont(FontFactory.HELVETICA, 10);
    private static final Font FOOT = FontFactory.getFont(FontFactory.HELVETICA, 8);
    private static final Font MARK = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 72, new Color(215, 215, 215));

    /** One label and value of a key-value block. */
    public record KeyValue(String label, String value) {
    }

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final Document document = new Document(PageSize.A4, 40, 40, 40, 50);
    private final PdfWriter writer;
    private String watermark;
    private String footer = "";

    public PdfDocumentBuilder(String title) {
        try {
            writer = PdfWriter.getInstance(document, out);
        } catch (DocumentException e) {
            throw new PdfException("Cannot start the PDF document.", e);
        }
        document.addTitle(title);
        writer.setPageEvent(new PageDecorations());
        document.open();
    }

    /** Centered organization name and (optional) address. */
    public PdfDocumentBuilder header(String name, String address) {
        add(centered(name, TITLE));
        if (address != null && !address.isBlank()) {
            add(centered(address, ADDRESS));
        }
        return this;
    }

    public PdfDocumentBuilder heading(String text) {
        Paragraph p = new Paragraph(text, HEADING);
        p.setSpacingBefore(12);
        p.setSpacingAfter(6);
        add(p);
        return this;
    }

    /** Two label/value pairs per row; a null value prints as a dash. */
    public PdfDocumentBuilder keyValues(List<KeyValue> pairs) {
        PdfPTable table = new PdfPTable(4);
        table.setWidthPercentage(100);
        try {
            table.setWidths(new float[] {1.2f, 2f, 1.2f, 2f});
        } catch (DocumentException e) {
            throw new PdfException("Bad key-value layout.", e);
        }
        for (KeyValue pair : pairs) {
            table.addCell(plain(pair.label(), LABEL));
            table.addCell(plain(pair.value() == null ? "-" : pair.value(), BODY));
        }
        table.completeRow();
        add(table);
        return this;
    }

    /** A bordered table with a header row; null cells print empty. */
    public PdfDocumentBuilder table(List<String> headers, List<List<String>> rows, float[] relativeWidths) {
        PdfPTable table = new PdfPTable(headers.size());
        table.setWidthPercentage(100);
        table.setHeaderRows(1);
        table.setSpacingBefore(4);
        try {
            table.setWidths(relativeWidths);
        } catch (DocumentException e) {
            throw new PdfException("Bad table layout.", e);
        }
        for (String h : headers) {
            PdfPCell cell = new PdfPCell(new Phrase(h, LABEL));
            cell.setBackgroundColor(new Color(235, 235, 235));
            cell.setPadding(4);
            table.addCell(cell);
        }
        for (List<String> row : rows) {
            for (String value : row) {
                PdfPCell cell = new PdfPCell(new Phrase(value == null ? "" : value, BODY));
                cell.setPadding(4);
                table.addCell(cell);
            }
        }
        add(table);
        return this;
    }

    /** Large diagonal text on every page, under the content. */
    public PdfDocumentBuilder watermark(String text) {
        this.watermark = text;
        return this;
    }

    public PdfDocumentBuilder footer(String text) {
        this.footer = text == null ? "" : text;
        return this;
    }

    public byte[] build() {
        document.close();
        return out.toByteArray();
    }

    private void add(Element element) {
        try {
            document.add(element);
        } catch (DocumentException e) {
            throw new PdfException("Cannot add content to the PDF document.", e);
        }
    }

    private static Paragraph centered(String text, Font font) {
        Paragraph p = new Paragraph(text, font);
        p.setAlignment(Element.ALIGN_CENTER);
        return p;
    }

    private static PdfPCell plain(String text, Font font) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font));
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPaddingBottom(4);
        return cell;
    }

    /** Raised when the PDF library fails; the message never carries document content. */
    public static final class PdfException extends RuntimeException {
        PdfException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Watermark and footer; the watermark text is fixed when the first page ends (set it before adding content). */
    private final class PageDecorations extends PdfPageEventHelper {
        @Override
        public void onEndPage(PdfWriter w, Document d) {
            Rectangle page = d.getPageSize();
            if (watermark != null) {
                PdfContentByte under = w.getDirectContentUnder();
                ColumnText.showTextAligned(under, Element.ALIGN_CENTER, new Phrase(watermark, MARK),
                        page.getWidth() / 2, page.getHeight() / 2, 45);
            }
            String text = footer.isEmpty() ? "Page " + w.getPageNumber()
                    : footer + "  |  Page " + w.getPageNumber();
            ColumnText.showTextAligned(w.getDirectContent(), Element.ALIGN_CENTER, new Phrase(text, FOOT),
                    page.getWidth() / 2, 25, 0);
        }
    }
}
