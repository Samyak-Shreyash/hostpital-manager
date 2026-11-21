package com.hms.bedmanagement.application;

import com.hms.audit.api.AuditEntry;
import com.hms.audit.api.AuditOutcome;
import com.hms.audit.api.AuditRecorder;
import com.hms.bedmanagement.application.AvailabilityView.BedAvailability;
import com.hms.bedmanagement.application.AvailabilityView.WardAvailability;
import com.hms.bedmanagement.application.AvailabilityView.WardRef;
import com.hms.bedmanagement.infrastructure.BedRepository;
import com.hms.bedmanagement.infrastructure.BedRepository.BedRow;
import com.hms.bedmanagement.infrastructure.WardRepository;
import com.hms.bedmanagement.infrastructure.WardRepository.WardRow;
import com.hms.shared.PageResult;
import com.hms.shared.domain.BusinessRuleException;
import com.hms.shared.domain.ConflictException;
import com.hms.shared.domain.NotFoundException;
import com.hms.shared.id.UuidV7Generator;
import com.hms.shared.tenancy.OrganizationPrincipal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Beds and their availability ([E8-1]). Never deleted: a bed is retired with {@code OUT_OF_SERVICE}. */
@Service
public class BedService {

    static final String ENTITY = "BED";
    static final String ACTIVE = "ACTIVE";
    static final String OUT_OF_SERVICE = "OUT_OF_SERVICE";

    private final BedRepository beds;
    private final WardRepository wards;
    private final BedOccupancy occupancy;
    private final AuditRecorder audit;
    private final UuidV7Generator ids;
    private final Clock clock;

    public BedService(BedRepository beds, WardRepository wards, BedOccupancy occupancy, AuditRecorder audit,
            UuidV7Generator ids, Clock clock) {
        this.beds = beds;
        this.wards = wards;
        this.occupancy = occupancy;
        this.audit = audit;
        this.ids = ids;
        this.clock = clock;
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    @Transactional(readOnly = true)
    public PageResult<BedView> list(OrganizationPrincipal actor, UUID wardId, String status, int page, int size) {
        WardService.checkPage(page, size);
        UUID org = actor.organizationId();
        wards.find(org, wardId).orElseThrow(WardService::notFound);
        List<BedView> items = beds.listByWard(org, wardId, status, size, (long) page * size).stream()
                .map(BedService::view).toList();
        return new PageResult<>(items, page, size, beds.countByWard(org, wardId, status));
    }

    @Transactional
    public BedView create(OrganizationPrincipal actor, UUID wardId, String bedNo, String bedClass,
            UUID dailyRateServiceId) {
        UUID org = actor.organizationId();
        wards.find(org, wardId).orElseThrow(WardService::notFound);
        UUID id = ids.next();
        if (!beds.insert(id, org, wardId, bedNo.trim(), bedClass == null ? null : bedClass.trim(),
                dailyRateServiceId, now())) {
            throw new ConflictException("DUPLICATE_BED_NO", "Bed number already exists in this ward.");
        }
        audit.record(new AuditEntry(org, actor.userId(), "BED_CREATED", ENTITY, id, AuditOutcome.SUCCESS,
                List.of("wardId", "bedNo", "bedClass", "dailyRateServiceId", "status")));
        return view(beds.find(org, id).orElseThrow());
    }

    /**
     * Partial update; {@code version} must match. The bed row is locked first (lock order: bed, admission,
     * invoice). Taking an occupied bed out of service is refused with {@code 409 BED_OCCUPIED}.
     */
    @Transactional
    public BedView update(OrganizationPrincipal actor, UUID id, long version, String bedClass, UUID dailyRateServiceId,
            String status) {
        if (bedClass == null && dailyRateServiceId == null && status == null) {
            throw new BusinessRuleException("VALIDATION_ERROR", "Provide bedClass, dailyRateServiceId and/or status.");
        }
        UUID org = actor.organizationId();
        BedRow current = beds.findForUpdate(org, id).orElseThrow(BedService::notFound);
        if (current.version() != version) {
            throw WardService.concurrentModification();
        }
        List<String> changed = new ArrayList<>();
        String newClass = current.bedClass();
        if (bedClass != null && !bedClass.trim().equals(current.bedClass())) {
            newClass = bedClass.trim();
            changed.add("bedClass");
        }
        UUID newRate = current.dailyRateServiceId();
        if (dailyRateServiceId != null && !dailyRateServiceId.equals(current.dailyRateServiceId())) {
            newRate = dailyRateServiceId;
            changed.add("dailyRateServiceId");
        }
        String newStatus = current.status();
        if (status != null && !status.equals(current.status())) {
            setStatusAllowed(org, id, status);
            newStatus = status;
            changed.add("status");
        }
        if (changed.isEmpty()) {
            return view(current);
        }
        if (!beds.update(org, id, version, newClass, newRate, newStatus, now())) {
            throw WardService.concurrentModification();
        }
        audit.record(new AuditEntry(org, actor.userId(), "BED_UPDATED", ENTITY, id, AuditOutcome.SUCCESS, changed));
        return view(beds.find(org, id).orElseThrow());
    }

    private void setStatusAllowed(UUID org, UUID bedId, String newStatus) {
        if (OUT_OF_SERVICE.equals(newStatus) && occupancy.isOccupied(org, bedId)) {
            throw new ConflictException("BED_OCCUPIED", "The bed is occupied and cannot be taken out of service.");
        }
    }

    /** Beds per ward with free/occupied/out-of-service counts. Without {@code wardId}: every ACTIVE ward. */
    @Transactional(readOnly = true)
    public AvailabilityView availability(OrganizationPrincipal actor, UUID wardId) {
        UUID org = actor.organizationId();
        if (wardId != null) {
            wards.find(org, wardId).orElseThrow(WardService::notFound);
        }
        List<WardRow> wardRows = wards.forAvailability(org, wardId);
        List<BedRow> bedRows = beds.listForWards(org, wardRows.stream().map(WardRow::id).toList());
        Map<UUID, UUID> held = occupancy.admissionsByOccupiedBed(org, bedRows.stream().map(BedRow::id).toList());
        Map<UUID, List<BedRow>> byWard = new HashMap<>();
        bedRows.forEach(b -> byWard.computeIfAbsent(b.wardId(), k -> new ArrayList<>()).add(b));

        List<WardAvailability> result = new ArrayList<>();
        for (WardRow w : wardRows) {
            WardRef ref = new WardRef(w.id(), w.name(), w.careType());
            List<BedAvailability> items = new ArrayList<>();
            int occupied = 0;
            int outOfService = 0;
            int available = 0;
            for (BedRow b : byWard.getOrDefault(w.id(), List.of())) {
                boolean isOccupied = held.containsKey(b.id());
                items.add(new BedAvailability(b.id(), b.bedNo(), b.bedClass(), ref, b.status(), isOccupied,
                        held.get(b.id())));
                if (isOccupied) {
                    occupied++;
                } else if (OUT_OF_SERVICE.equals(b.status())) {
                    outOfService++;
                } else {
                    available++;
                }
            }
            result.add(new WardAvailability(ref, w.code(), w.status(), items.size(), occupied, available,
                    outOfService, items));
        }
        return new AvailabilityView(result);
    }

    private static NotFoundException notFound() {
        return new NotFoundException("Bed not found.");
    }

    private static BedView view(BedRow r) {
        return new BedView(r.id(), r.wardId(), r.bedNo(), r.bedClass(), r.dailyRateServiceId(), r.status(),
                r.version(), r.createdAt(), r.updatedAt());
    }
}
