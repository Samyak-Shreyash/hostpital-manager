package com.hms.billing.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class InvoicePdfTest {

    private static InvoicePdf.Content content(String outstanding) {
        return new InvoicePdf.Content("Synthetic Clinic", "1 Test Road", "INV/2026-27/000001", "20 Nov 2026",
                "Test Patient", "UHID-0001", "PAID", false, List.of(), "100.00", "0.00", "0.00", "100.00", "0.00",
                "150.00", "0.00", outstanding);
    }

    @Test
    void negativeOutstandingIsClampedAndPrintedAsCreditBalance() {
        List<List<String>> rows = InvoicePdf.summaryRows(content("-50.00"));
        assertThat(rows).contains(List.of("Outstanding", "0.00"), List.of("Credit balance", "50.00"));
        assertThat(rows).noneMatch(r -> r.get(1).startsWith("-"));
        assertThat(InvoicePdf.render(content("-50.00"))).isNotEmpty();
    }

    @Test
    void nonNegativeOutstandingIsPrintedAsIsWithoutCreditBalance() {
        List<List<String>> rows = InvoicePdf.summaryRows(content("25.00"));
        assertThat(rows).contains(List.of("Outstanding", "25.00"));
        assertThat(rows).noneMatch(r -> r.get(0).equals("Credit balance"));
    }
}
