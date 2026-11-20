package com.hms.billing.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** [E7-2] Financial year April to March: the label of an invoice number and its sequence period. */
class FinancialYearTest {

    @Test
    void aprilStartsTheYearAndMarchEndsIt() {
        assertThat(InvoiceService.financialYear(LocalDate.of(2026, 4, 1))).isEqualTo("2026-27");
        assertThat(InvoiceService.financialYear(LocalDate.of(2027, 3, 31))).isEqualTo("2026-27");
        assertThat(InvoiceService.financialYear(LocalDate.of(2026, 3, 31))).isEqualTo("2025-26");
        assertThat(InvoiceService.financialYear(LocalDate.of(2099, 12, 1))).isEqualTo("2099-00");
    }
}
