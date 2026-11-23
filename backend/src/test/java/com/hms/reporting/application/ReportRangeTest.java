package com.hms.reporting.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hms.shared.domain.BusinessRuleException;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class ReportRangeTest {

    @Test
    void rejectsFromAfterTo() {
        assertThatThrownBy(() -> ReportRange.of(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 1)))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void rejectsMoreThan366Days() {
        assertThatThrownBy(() -> ReportRange.of(LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 2)))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void accepts366Days() {
        assertThat(ReportRange.of(LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1))).isNotNull();
    }

    @Test
    void rejectsNulls() {
        assertThatThrownBy(() -> ReportRange.of(null, LocalDate.of(2026, 1, 1)))
                .isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> ReportRange.of(LocalDate.of(2026, 1, 1), null))
                .isInstanceOf(BusinessRuleException.class);
    }
}
