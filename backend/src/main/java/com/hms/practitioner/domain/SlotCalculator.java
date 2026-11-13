package com.hms.practitioner.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Free-slot computation ([E4-3], PRC-4): weekly windows (local wall-clock times in the organization's zone) minus
 * leave, as UTC instants. Pure: no clock, no database.
 *
 * <p>Timezone handling never assumes a fixed offset. Each window's local start and end on a given date are
 * resolved in the zone ({@code LocalDateTime.atZone}: a time inside a DST gap moves forward by the gap, an
 * ambiguous time takes the earlier offset), and slots are then stepped in real elapsed time from the window start.
 * On a day that springs forward a window therefore has fewer slots, and on a day that falls back more; a slot that
 * would cross the window end is dropped. A partial leave block is resolved the same way and removes every slot it
 * overlaps; a whole-day leave removes the date.
 */
public final class SlotCalculator {

    /** One weekly window. {@code weekday} is ISO-8601 (1 = Monday ... 7 = Sunday); {@code end > start}. */
    public record Window(int weekday, LocalTime start, LocalTime end, int slotMinutes) {
    }

    /** One active leave entry: both times null = whole day, otherwise the local block {@code [start, end)}. */
    public record Leave(LocalDate date, LocalTime start, LocalTime end) {

        public boolean wholeDay() {
            return start == null;
        }
    }

    /** Half-open {@code [start, end)}. */
    public record Slot(Instant start, Instant end) {

        boolean overlaps(Instant otherStart, Instant otherEnd) {
            return start.isBefore(otherEnd) && otherStart.isBefore(end);
        }
    }

    private SlotCalculator() {
    }

    /** Free slots on the local dates {@code from..to} (inclusive), ordered by start. */
    public static List<Slot> freeSlots(List<Window> windows, List<Leave> leave, LocalDate from, LocalDate to,
            ZoneId zone) {
        List<Slot> result = new ArrayList<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            List<Slot> blocked = blockedOn(leave, date, zone);
            if (blocked == null) {
                continue; // whole day off
            }
            for (Window window : windows) {
                if (window.weekday() != date.getDayOfWeek().getValue()) {
                    continue;
                }
                Instant windowEnd = date.atTime(window.end()).atZone(zone).toInstant();
                Duration length = Duration.ofMinutes(window.slotMinutes());
                Instant start = date.atTime(window.start()).atZone(zone).toInstant();
                for (Instant end = start.plus(length); !end.isAfter(windowEnd); start = end, end = end.plus(length)) {
                    Slot slot = new Slot(start, end);
                    if (blocked.stream().noneMatch(b -> slot.overlaps(b.start(), b.end()))) {
                        result.add(slot);
                    }
                }
            }
        }
        result.sort(Comparator.comparing(Slot::start));
        return result;
    }

    /**
     * Whether {@code [start, end)} lies inside one window and touches no leave. Checks the local dates around the
     * interval, so a zone far from UTC cannot push the matching window onto a neighbouring date.
     */
    public static boolean isAvailable(List<Window> windows, List<Leave> leave, Instant start, Instant end,
            ZoneId zone) {
        if (!start.isBefore(end)) {
            return false;
        }
        LocalDate first = start.atZone(zone).toLocalDate().minusDays(1);
        LocalDate last = end.atZone(zone).toLocalDate().plusDays(1);
        boolean inWindow = false;
        for (LocalDate date = first; !date.isAfter(last); date = date.plusDays(1)) {
            List<Slot> blocked = blockedOn(leave, date, zone);
            Instant dayStart = date.atStartOfDay(zone).toInstant();
            Instant dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant();
            if (blocked == null) {
                if (start.isBefore(dayEnd) && dayStart.isBefore(end)) {
                    return false;
                }
                continue;
            }
            if (blocked.stream().anyMatch(b -> b.overlaps(start, end))) {
                return false;
            }
            for (Window window : windows) {
                if (window.weekday() != date.getDayOfWeek().getValue()) {
                    continue;
                }
                Instant ws = date.atTime(window.start()).atZone(zone).toInstant();
                Instant we = date.atTime(window.end()).atZone(zone).toInstant();
                if (!start.isBefore(ws) && !end.isAfter(we)) {
                    inWindow = true;
                }
            }
        }
        return inWindow;
    }

    /** Partial leave blocks on {@code date} as instants; {@code null} when the whole day is off. */
    private static List<Slot> blockedOn(List<Leave> leave, LocalDate date, ZoneId zone) {
        List<Slot> blocked = new ArrayList<>();
        for (Leave l : leave) {
            if (!l.date().equals(date)) {
                continue;
            }
            if (l.wholeDay()) {
                return null;
            }
            blocked.add(new Slot(date.atTime(l.start()).atZone(zone).toInstant(),
                    date.atTime(l.end()).atZone(zone).toInstant()));
        }
        return blocked;
    }
}
