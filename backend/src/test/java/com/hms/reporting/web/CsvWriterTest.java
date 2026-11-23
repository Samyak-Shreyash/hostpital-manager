package com.hms.reporting.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.hms.reporting.application.ReportResult;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CsvWriterTest {

    private static String csv(List<String> columns, Object... values) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < columns.size(); i++) {
            row.put(columns.get(i), values[i]);
        }
        return CsvWriter.write(new ReportResult("r", LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 2),
                Instant.EPOCH, columns, List.of(row)));
    }

    @Test
    void headerAndRowEndWithCrLf() {
        assertThat(csv(List.of("date", "count"), "2026-11-01", 3)).isEqualTo("date,count\r\n2026-11-01,3\r\n");
    }

    @Test
    void quotesCommasAndQuotes() {
        assertThat(csv(List.of("name"), "a,b")).endsWith("\"a,b\"\r\n");
        assertThat(csv(List.of("name"), "say \"hi\"")).endsWith("\"say \"\"hi\"\"\"\r\n");
    }

    @Test
    void textStartingWithFormulaCharIsPrefixed() {
        assertThat(csv(List.of("name"), "=SUM(A1)")).endsWith("'=SUM(A1)\r\n");
        assertThat(csv(List.of("name"), "-5")).endsWith("'-5\r\n");
        assertThat(csv(List.of("name"), "@x")).endsWith("'@x\r\n");
        assertThat(csv(List.of("name"), "+1")).endsWith("'+1\r\n");
    }

    @Test
    void numericAndMoneyCellsAreNotPrefixed() {
        assertThat(csv(List.of("count"), -3)).endsWith("-3\r\n");
        assertThat(csv(List.of("amount"), "-100.00")).endsWith("-100.00\r\n");
        for (String col : List.of("collected", "refunded", "net", "total", "credited", "paid", "balance")) {
            assertThat(csv(List.of(col), "-1.50")).endsWith("-1.50\r\n");
        }
    }
}
