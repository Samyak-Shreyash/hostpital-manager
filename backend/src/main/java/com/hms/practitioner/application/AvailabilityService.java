package com.hms.practitioner.application;

import com.hms.audit.api.AuditEntry;
import com.hms.audit.api.AuditOutcome;
import com.hms.audit.api.AuditRecorder;
import com.hms.organization.api.OrganizationDirectory;
import com.hms.organization.api.OrganizationSummary;
import com.hms.practitioner.api.PractitionerAvailability;
import com.hms.practitioner.domain.SlotCalculator;
import com.hms.practitioner.domain.SlotCalculator.Leave;
import com.hms.practitioner.domain.SlotCalculator.Window;
import com.hms.practitioner.infrastructure.AvailabilityRepository;
import com.hms.practitioner.infrastructure.AvailabilityRepository.ExceptionRow;
import com.hms.practitioner.infrastructure.AvailabilityRepository.WindowRow;
import com.hms.practitioner.infrastructure.PractitionerRepository;
import com.hms.practitioner.infrastructure.PractitionerRepository.PractitionerRow;
import com.hms.shared.domain.BusinessRuleException;
import com.hms.shared.domain.ConflictException;
import com.hms.shared.domain.NotFoundException;
import com.hms.shared.id.UuidV7Generator;
import com.hms.shared.tenancy.OrganizationPrincipal;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Weekly availability, leave exceptions and free slots ([E4-3], PRC-4, api-design.md section 5).
 *
 * <p>Windows are local wall-clock times in the organization's timezone ({@link OrganizationDirectory}); slots are
 * returned as UTC instants. The weekly template is replaced as a set under the practitioner row lock with the
 * practitioner's {@code version}; overlapping windows are refused here (clear 422) and by the V20 exclusion
 * constraint. Leave (owner decision 2026-11-13: a date plus an optional time range) is never deleted, only
 * cancelled. Bookings do not exist yet ([E5-*]): when they do, a clashing booking is flagged by the appointment
 * module, never cancelled by a leave entry.
 */
@Service
public class AvailabilityService implements PractitionerAvailability {

    static final String EXCEPTION_ENTITY = "AVAILABILITY_EXCEPTION";
    static final int MAX_WINDOWS = 50;
    static final int MAX_RANGE_DAYS = 31;
    static final int MAX_EXCEPTIONS = 500;
    private static final Pattern HH_MM = Pattern.compile("([01]\\d|2[0-3]):[0-5]\\d");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final PractitionerRepository practitioners;
    private final AvailabilityRepository repository;
    private final OrganizationDirectory organizations;
    private final AuditRecorder audit;
    private final UuidV7Generator ids;
    private final Clock clock;

    public AvailabilityService(PractitionerRepository practitioners, AvailabilityRepository repository,
            OrganizationDirectory organizations, AuditRecorder audit, UuidV7Generator ids, Clock clock) {
        this.practitioners = practitioners;
        this.repository = repository;
        this.organizations = organizations;
        this.audit = audit;
        this.ids = ids;
        this.clock = clock;
    }

    // --- views and commands -------------------------------------------------------------------------------------

    /** One weekly window as the API carries it: ISO weekday, "HH:mm" local times. */
    public record WindowView(int weekday, String start, String end, int slotMinutes) {
    }

    /** The weekly template; {@code version} is the practitioner's. */
    public record AvailabilityView(UUID practitionerId, String timezone, List<WindowView> windows, long version) {
    }

    /** A window as requested; {@code slotMinutes} null = the practitioner's default. */
    public record WindowInput(Integer weekday, String start, String end, Integer slotMinutes) {
    }

    public record ExceptionView(UUID id, UUID practitionerId, LocalDate date, String start, String end,
            boolean wholeDay, String status, long version, Instant createdAt, Instant updatedAt) {
    }

    public record SlotsView(UUID practitionerId, String timezone, LocalDate from, LocalDate to,
            List<TimeSlot> slots) {
    }

    // --- weekly template ----------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public AvailabilityView get(OrganizationPrincipal actor, UUID practitionerId) {
        UUID org = actor.organizationId();
        PractitionerRow p = practitioners.find(org, practitionerId).orElseThrow(AvailabilityService::notFound);
        return view(org, p);
    }

    @Transactional
    public AvailabilityView replace(OrganizationPrincipal actor, UUID practitionerId, Long version,
            List<WindowInput> windows) {
        UUID org = actor.organizationId();
        PractitionerRow current = practitioners.lock(org, practitionerId).orElseThrow(AvailabilityService::notFound);
        if (version == null) {
            throw invalid("version is required.");
        }
        if (version != current.version()) {
            throw concurrentModification();
        }
        List<WindowRow> requested = windows(windows, current.defaultSlotMinutes());
        List<WindowRow> existing = repository.windows(org, practitionerId);
        if (sorted(requested).equals(sorted(existing))) {
            return view(org, current);
        }
        Instant now = now();
        if (!practitioners.touch(org, practitionerId, version, now)) {
            throw concurrentModification();
        }
        repository.replaceWindows(org, practitionerId, requested.stream().map(w -> ids.next()).toList(), requested,
                now);
        audit.record(new AuditEntry(org, actor.userId(), "PRACTITIONER_AVAILABILITY_CHANGED",
                PractitionerService.ENTITY, practitionerId, AuditOutcome.SUCCESS, List.of("windows")));
        return view(org, practitioners.find(org, practitionerId).orElseThrow());
    }

    // --- leave --------------------------------------------------------------------------------------------------

    @Transactional
    public ExceptionView createException(OrganizationPrincipal actor, UUID practitionerId, LocalDate date,
            String start, String end) {
        UUID org = actor.organizationId();
        practitioners.find(org, practitionerId).orElseThrow(AvailabilityService::notFound);
        if (date == null) {
            throw invalid("date is required.");
        }
        LocalTime from = null;
        LocalTime to = null;
        if (start != null || end != null) {
            if (start == null || end == null) {
                throw invalid("start and end must be given together (omit both for a whole day).");
            }
            from = time(start, "start");
            to = time(end, "end");
            if (!to.isAfter(from)) {
                throw invalid("end must be after start.");
            }
        }
        UUID id = ids.next();
        repository.insertException(id, org, practitionerId, date, from, to, actor.userId(), now());
        List<String> fields = new ArrayList<>(List.of("practitionerId", "date"));
        if (from != null) {
            fields.add("start");
            fields.add("end");
        }
        audit.record(new AuditEntry(org, actor.userId(), "AVAILABILITY_EXCEPTION_CREATED", EXCEPTION_ENTITY, id,
                AuditOutcome.SUCCESS, fields));
        return view(repository.exception(org, practitionerId, id).orElseThrow());
    }

    @Transactional(readOnly = true)
    public List<ExceptionView> exceptions(OrganizationPrincipal actor, UUID practitionerId, String from, String to) {
        UUID org = actor.organizationId();
        practitioners.find(org, practitionerId).orElseThrow(AvailabilityService::notFound);
        LocalDate fromDate = from == null ? null : date(from, "from");
        LocalDate toDate = to == null ? null : date(to, "to");
        if (fromDate != null && toDate != null && toDate.isBefore(fromDate)) {
            throw invalid("to must not be before from.");
        }
        return repository.exceptions(org, practitionerId, fromDate, toDate, false, MAX_EXCEPTIONS).stream()
                .map(AvailabilityService::view).toList();
    }

    /** ACTIVE -> CANCELLED. Cancelling an already cancelled entry is a no-op. */
    @Transactional
    public ExceptionView cancelException(OrganizationPrincipal actor, UUID practitionerId, UUID exceptionId,
            Long version) {
        UUID org = actor.organizationId();
        ExceptionRow current = repository.exception(org, practitionerId, exceptionId)
                .orElseThrow(() -> new NotFoundException("Availability exception not found."));
        if (version == null) {
            throw invalid("version is required.");
        }
        if (version != current.version()) {
            throw concurrentModification();
        }
        if ("CANCELLED".equals(current.status())) {
            return view(current);
        }
        if (!repository.cancel(org, exceptionId, version, actor.userId(), now())) {
            throw concurrentModification();
        }
        audit.record(new AuditEntry(org, actor.userId(), "AVAILABILITY_EXCEPTION_CANCELLED", EXCEPTION_ENTITY,
                exceptionId, AuditOutcome.SUCCESS, List.of("status")));
        return view(repository.exception(org, practitionerId, exceptionId).orElseThrow());
    }

    // --- slots --------------------------------------------------------------------------------------------------

    /** {@code date} alone is one day; otherwise {@code from} and {@code to} (inclusive, at most 31 days). */
    @Transactional(readOnly = true)
    public SlotsView slots(OrganizationPrincipal actor, UUID practitionerId, String date, String from, String to) {
        UUID org = actor.organizationId();
        practitioners.find(org, practitionerId).orElseThrow(AvailabilityService::notFound);
        LocalDate fromDate;
        LocalDate toDate;
        if (date != null) {
            if (from != null || to != null) {
                throw invalid("give either date or from/to, not both.");
            }
            fromDate = date(date, "date");
            toDate = fromDate;
        } else {
            if (from == null || to == null) {
                throw invalid("date, or both from and to, are required.");
            }
            fromDate = date(from, "from");
            toDate = date(to, "to");
        }
        checkRange(fromDate, toDate);
        return new SlotsView(practitionerId, zone(org).getId(), fromDate, toDate,
                freeSlots(org, practitionerId, fromDate, toDate));
    }

    @Override
    @Transactional(readOnly = true)
    public List<TimeSlot> freeSlots(UUID organizationId, UUID practitionerId, LocalDate from, LocalDate to) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        checkRange(from, to);
        if (!active(organizationId, practitionerId)) {
            return List.of();
        }
        return SlotCalculator.freeSlots(windowsOf(organizationId, practitionerId),
                        leaveOf(organizationId, practitionerId, from, to), from, to, zone(organizationId))
                .stream().map(s -> new TimeSlot(s.start(), s.end())).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isAvailable(UUID organizationId, UUID practitionerId, Instant start, Instant end) {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        if (!start.isBefore(end) || !active(organizationId, practitionerId)) {
            return false;
        }
        ZoneId zone = zone(organizationId);
        LocalDate first = start.atZone(zone).toLocalDate().minusDays(1);
        LocalDate last = end.atZone(zone).toLocalDate().plusDays(1);
        return SlotCalculator.isAvailable(windowsOf(organizationId, practitionerId),
                leaveOf(organizationId, practitionerId, first, last), start, end, zone);
    }

    // --- helpers ------------------------------------------------------------------------------------------------

    private boolean active(UUID org, UUID practitionerId) {
        return practitioners.find(org, practitionerId).map(p -> "ACTIVE".equals(p.status())).orElse(false);
    }

    private List<Window> windowsOf(UUID org, UUID practitionerId) {
        return repository.windows(org, practitionerId).stream()
                .map(w -> new Window(w.weekday(), w.start(), w.end(), w.slotMinutes())).toList();
    }

    private List<Leave> leaveOf(UUID org, UUID practitionerId, LocalDate from, LocalDate to) {
        return repository.exceptions(org, practitionerId, from, to, true, Integer.MAX_VALUE).stream()
                .map(e -> new Leave(e.date(), e.start(), e.end())).toList();
    }

    private ZoneId zone(UUID org) {
        String id = organizations.find(org).map(OrganizationSummary::timezone)
                .orElseThrow(() -> new IllegalStateException("organization not found"));
        return ZoneId.of(id);
    }

    private AvailabilityView view(UUID org, PractitionerRow p) {
        List<WindowView> windows = repository.windows(org, p.id()).stream()
                .map(w -> new WindowView(w.weekday(), TIME.format(w.start()), TIME.format(w.end()), w.slotMinutes()))
                .toList();
        return new AvailabilityView(p.id(), zone(org).getId(), windows, p.version());
    }

    private static ExceptionView view(ExceptionRow e) {
        return new ExceptionView(e.id(), e.practitionerId(), e.date(),
                e.start() == null ? null : TIME.format(e.start()), e.end() == null ? null : TIME.format(e.end()),
                e.start() == null, e.status(), e.version(), e.createdAt(), e.updatedAt());
    }

    /** Validates the requested template: shapes, per-window rules and no overlap on the same weekday. */
    private static List<WindowRow> windows(List<WindowInput> inputs, int defaultSlotMinutes) {
        if (inputs == null) {
            throw invalid("windows is required (an empty list clears the template).");
        }
        if (inputs.size() > MAX_WINDOWS) {
            throw invalid("at most " + MAX_WINDOWS + " windows.");
        }
        List<WindowRow> rows = new ArrayList<>();
        for (WindowInput in : inputs) {
            if (in == null || in.weekday() == null || in.weekday() < 1 || in.weekday() > 7) {
                throw invalid("weekday must be 1 (Monday) to 7 (Sunday).");
            }
            LocalTime start = time(in.start(), "start");
            LocalTime end = time(in.end(), "end");
            if (!end.isAfter(start)) {
                throw invalid("end must be after start.");
            }
            int slot = in.slotMinutes() == null ? defaultSlotMinutes : in.slotMinutes();
            if (slot < 5 || slot > 120) {
                throw invalid("slotMinutes must be 5 to 120.");
            }
            if (ChronoUnit.MINUTES.between(start, end) < slot) {
                throw invalid("a window must be at least one slot long.");
            }
            rows.add(new WindowRow(in.weekday(), start, end, slot));
        }
        List<WindowRow> ordered = sorted(rows);
        for (int i = 1; i < ordered.size(); i++) {
            WindowRow prev = ordered.get(i - 1);
            WindowRow cur = ordered.get(i);
            if (prev.weekday() == cur.weekday() && cur.start().isBefore(prev.end())) {
                throw new BusinessRuleException("AVAILABILITY_OVERLAP",
                        "windows on the same weekday must not overlap.");
            }
        }
        return ordered;
    }

    private static List<WindowRow> sorted(List<WindowRow> rows) {
        return rows.stream().sorted(Comparator.comparingInt(WindowRow::weekday).thenComparing(WindowRow::start))
                .toList();
    }

    private static void checkRange(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            throw invalid("to must not be before from.");
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_RANGE_DAYS) {
            throw invalid("the date range must be at most " + MAX_RANGE_DAYS + " days.");
        }
    }

    private static LocalTime time(String value, String field) {
        if (value == null || !HH_MM.matcher(value).matches()) {
            throw invalid(field + " must be a time HH:mm.");
        }
        return LocalTime.parse(value, TIME);
    }

    private static LocalDate date(String value, String field) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeException e) {
            throw invalid(field + " must be a date YYYY-MM-DD.");
        }
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static BusinessRuleException invalid(String message) {
        return new BusinessRuleException("VALIDATION_ERROR", message);
    }

    private static NotFoundException notFound() {
        return new NotFoundException("Doctor not found.");
    }

    private static ConflictException concurrentModification() {
        return new ConflictException("CONCURRENT_MODIFICATION",
                "The resource was changed by someone else. Reload and try again.");
    }
}
