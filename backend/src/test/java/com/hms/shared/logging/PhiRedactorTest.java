package com.hms.shared.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.json.JsonWriter.MemberPath;

class PhiRedactorTest {

    @Test
    void masksContactAndIdentityNumbersInFreeText() {
        String line = "Reminder for patient.one@example.test, mobile +91 9876543210, alt 09123456780,"
                + " ABHA 91-1234-5678-9012, Aadhaar 2345 6789 0123";

        String masked = PhiRedactor.mask(line);

        assertThat(masked)
                .doesNotContain("patient.one@example.test", "9876543210", "9123456780", "91-1234-5678-9012",
                        "2345 6789 0123")
                .contains("[EMAIL]", "[PHONE]", "[ABHA]", "[AADHAAR]");
    }

    @Test
    void leavesIdsAndOrdinaryNumbersAlone() {
        String line = "Invoice INV-2026-000123 for encounter 0192f3a4-5b6c-7d8e-9f01-234567890123 totals 1250.00";

        assertThat(PhiRedactor.mask(line)).isEqualTo(line);
    }

    @Test
    void redactsSensitiveKeysWhateverTheirSpelling() {
        assertThat(PhiRedactor.process(MemberPath.of("password"), "s3cret-synthetic")).isEqualTo(PhiRedactor.REDACTED);
        assertThat(PhiRedactor.process(MemberPath.of("patient_name"), "Test Patient")).isEqualTo(PhiRedactor.REDACTED);
        assertThat(PhiRedactor.process(MemberPath.of("phoneNumber"), 9876543210L)).isEqualTo(PhiRedactor.REDACTED);
    }

    @Test
    void trustsPlatformFields() {
        assertThat(PhiRedactor.process(MemberPath.of("correlationId"), "9876543210")).isEqualTo("9876543210");
        assertThat(PhiRedactor.process(MemberPath.of("message"), "call 9876543210")).isEqualTo("call [PHONE]");
        assertThat(PhiRedactor.process(MemberPath.of("count"), 42)).isEqualTo(42);
    }

    @Test
    void moduleIsThePackageSegmentAfterComHms() {
        assertThat(ModuleName.of("com.hms.billing.application.IssueInvoice")).isEqualTo("billing");
        assertThat(ModuleName.of("com.hms.HmsApplication")).isNull();
        assertThat(ModuleName.of("org.springframework.web.Foo")).isNull();
    }
}
