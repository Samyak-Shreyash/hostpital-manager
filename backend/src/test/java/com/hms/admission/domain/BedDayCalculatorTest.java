package com.hms.admission.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.hms.admission.domain.BedDayCalculator.BedDay;
import com.hms.admission.domain.BedDayCalculator.Stay;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** BR-B6 rule [A] as fixed by ruling R2; times are IST (Asia/Kolkata). */
class BedDayCalculatorTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final UUID A = UUID.fromString("00000000-0000-7000-8000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-7000-8000-00000000000b");

    private static Instant ist(int day, int hour, int minute) {
        return LocalDateTime.of(2026, 11, day, hour, minute).atZone(IST).toInstant();
    }

    /** Expected "21:A,22:B" style map of November dates to bed. */
    private static List<BedDay> days(String spec) {
        List<BedDay> out = new ArrayList<>();
        for (String part : spec.split(",")) {
            String[] kv = part.split(":");
            out.add(new BedDay(LocalDate.of(2026, 11, Integer.parseInt(kv[0])), kv[1].equals("A") ? A : B));
        }
        return out;
    }

    static Stream<Arguments> cases() {
        return Stream.of(
                Arguments.of("same-day minimum 1", ist(21, 10, 0), ist(21, 18, 0),
                        List.of(new Stay(A, ist(21, 10, 0), null)), "21:A"),
                Arguments.of("crosses one midnight", ist(21, 23, 50), ist(22, 0, 10),
                        List.of(new Stay(A, ist(21, 23, 50), null)), "21:A"),
                Arguments.of("three nights", ist(21, 10, 0), ist(24, 9, 0),
                        List.of(new Stay(A, ist(21, 10, 0), null)), "21:A,22:A,23:A"),
                Arguments.of("transfer mid-day", ist(21, 10, 0), ist(24, 9, 0),
                        List.of(new Stay(A, ist(21, 10, 0), ist(22, 15, 0)), new Stay(B, ist(22, 15, 0), null)),
                        "21:A,22:B,23:B"),
                Arguments.of("transfer at midnight belongs to new bed", ist(21, 10, 0), ist(23, 9, 0),
                        List.of(new Stay(A, ist(21, 10, 0), ist(22, 0, 0)), new Stay(B, ist(22, 0, 0), null)),
                        "21:B,22:B"),
                Arguments.of("same-day transfer: bed at end", ist(21, 10, 0), ist(21, 20, 0),
                        List.of(new Stay(A, ist(21, 10, 0), ist(21, 15, 0)), new Stay(B, ist(21, 15, 0), null)),
                        "21:B"),
                Arguments.of("IST date not UTC", Instant.parse("2026-11-20T19:00:00Z"), ist(22, 8, 0),
                        List.of(new Stay(A, Instant.parse("2026-11-20T19:00:00Z"), null)), "21:A"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void chargedDays(String name, Instant admitted, Instant end, List<Stay> stays, String expected) {
        assertThat(BedDayCalculator.chargedDays(stays, admitted, end, IST)).containsExactlyElementsOf(days(expected));
    }

    @Test
    void closedStayEndingAtEndStillCoversSameDayEnd() {
        // discharge releases the bed at the end instant itself
        Instant end = ist(21, 18, 0);
        assertThat(BedDayCalculator.chargedDays(List.of(new Stay(A, ist(21, 10, 0), end)), ist(21, 10, 0), end, IST))
                .containsExactly(new BedDay(LocalDate.of(2026, 11, 21), A));
    }
}
