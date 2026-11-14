package com.hms.practitioner.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.hms.practitioner.domain.SlotCalculator.Leave;
import com.hms.practitioner.domain.SlotCalculator.Slot;
import com.hms.practitioner.domain.SlotCalculator.Window;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

/** [E4-3] Free-slot computation: windows, leave and timezone/DST behaviour (no fixed offset assumed). */
class SlotCalculatorTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    // 2026-11-16 is a Monday.
    private static final LocalDate MONDAY = LocalDate.of(2026, 11, 16);

    private static Window window(int weekday, String start, String end, int minutes) {
        return new Window(weekday, LocalTime.parse(start), LocalTime.parse(end), minutes);
    }

    @Test
    void windowsBecomeConsecutiveSlotsInUtc() {
        List<Slot> slots = SlotCalculator.freeSlots(List.of(window(1, "09:00", "10:00", 15)), List.of(), MONDAY,
                MONDAY, IST);
        assertThat(slots).hasSize(4);
        assertThat(slots.get(0).start()).isEqualTo(Instant.parse("2026-11-16T03:30:00Z"));
        assertThat(slots.get(3).end()).isEqualTo(Instant.parse("2026-11-16T04:30:00Z"));
    }

    @Test
    void onlyMatchingWeekdaysAndWholeSlotsAreProduced() {
        // 09:00-09:50 with 20-minute slots: 2 slots, the 10-minute remainder is dropped. Tuesday window ignored.
        List<Slot> slots = SlotCalculator.freeSlots(
                List.of(window(1, "09:00", "09:50", 20), window(2, "09:00", "12:00", 15)), List.of(), MONDAY,
                MONDAY, IST);
        assertThat(slots).extracting(Slot::start).containsExactly(Instant.parse("2026-11-16T03:30:00Z"),
                Instant.parse("2026-11-16T03:50:00Z"));
    }

    @Test
    void wholeDayLeaveRemovesTheDate() {
        List<Slot> slots = SlotCalculator.freeSlots(List.of(window(1, "09:00", "10:00", 15)),
                List.of(new Leave(MONDAY, null, null)), MONDAY, MONDAY.plusDays(7), IST);
        assertThat(slots).hasSize(4).allMatch(s -> s.start().isAfter(Instant.parse("2026-11-20T00:00:00Z")));
    }

    @Test
    void partialLeaveRemovesOverlappingSlotsOnly() {
        // Leave 09:20-09:40 overlaps the 09:15 and 09:30 slots.
        List<Slot> slots = SlotCalculator.freeSlots(List.of(window(1, "09:00", "10:00", 15)),
                List.of(new Leave(MONDAY, LocalTime.of(9, 20), LocalTime.of(9, 40))), MONDAY, MONDAY, IST);
        assertThat(slots).extracting(Slot::start).containsExactly(Instant.parse("2026-11-16T03:30:00Z"),
                Instant.parse("2026-11-16T04:15:00Z"));
    }

    @Test
    void localWallClockIsKeptAcrossADstChange() {
        Window nine = window(5, "09:00", "09:15", 15); // Fridays
        List<Slot> before = SlotCalculator.freeSlots(List.of(nine), List.of(), LocalDate.of(2026, 3, 6),
                LocalDate.of(2026, 3, 6), NEW_YORK);
        List<Slot> after = SlotCalculator.freeSlots(List.of(nine), List.of(), LocalDate.of(2026, 3, 13),
                LocalDate.of(2026, 3, 13), NEW_YORK);
        assertThat(before.get(0).start()).isEqualTo(Instant.parse("2026-03-06T14:00:00Z")); // EST, UTC-5
        assertThat(after.get(0).start()).isEqualTo(Instant.parse("2026-03-13T13:00:00Z")); // EDT, UTC-4
    }

    @Test
    void slotsFollowElapsedTimeOnTransitionDays() {
        Window night = window(7, "00:00", "04:00", 15); // Sundays
        // 2026-03-08 springs forward 02:00 -> 03:00: the window lasts 3 hours.
        List<Slot> spring = SlotCalculator.freeSlots(List.of(night), List.of(), LocalDate.of(2026, 3, 8),
                LocalDate.of(2026, 3, 8), NEW_YORK);
        // 2026-11-01 falls back 02:00 -> 01:00: the window lasts 5 hours.
        List<Slot> fall = SlotCalculator.freeSlots(List.of(night), List.of(), LocalDate.of(2026, 11, 1),
                LocalDate.of(2026, 11, 1), NEW_YORK);
        assertThat(spring).hasSize(12);
        assertThat(fall).hasSize(20);
        for (List<Slot> day : List.of(spring, fall)) {
            assertThat(day).allMatch(s -> Duration.between(s.start(), s.end()).toMinutes() == 15);
            for (int i = 1; i < day.size(); i++) {
                assertThat(day.get(i).start()).isEqualTo(day.get(i - 1).end());
            }
        }
    }

    @Test
    void isAvailableHonoursWindowsAndLeave() {
        List<Window> windows = List.of(window(1, "09:00", "12:00", 15));
        Instant nine = Instant.parse("2026-11-16T03:30:00Z");
        assertThat(SlotCalculator.isAvailable(windows, List.of(), nine, nine.plusSeconds(1800), IST)).isTrue();
        assertThat(SlotCalculator.isAvailable(windows, List.of(), nine.minusSeconds(60), nine.plusSeconds(900), IST))
                .isFalse();
        assertThat(SlotCalculator.isAvailable(windows,
                List.of(new Leave(MONDAY, LocalTime.of(9, 10), LocalTime.of(9, 20))), nine, nine.plusSeconds(900),
                IST)).isFalse();
        assertThat(SlotCalculator.isAvailable(windows, List.of(new Leave(MONDAY, null, null)), nine,
                nine.plusSeconds(900), IST)).isFalse();
        assertThat(SlotCalculator.isAvailable(windows, List.of(), nine.plusSeconds(86_400),
                nine.plusSeconds(87_300), IST)).isFalse(); // Tuesday
    }
}
