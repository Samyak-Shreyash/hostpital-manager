package com.hms.admission.application;

import com.hms.admission.domain.AdmissionStatus;
import com.hms.admission.domain.BedDayCalculator;
import com.hms.admission.domain.BedDayCalculator.BedDay;
import com.hms.admission.domain.BedDayCalculator.Stay;
import com.hms.admission.infrastructure.AdmissionRepository;
import com.hms.admission.infrastructure.AdmissionRepository.AdmissionRow;
import com.hms.audit.api.AuditEntry;
import com.hms.audit.api.AuditOutcome;
import com.hms.audit.api.AuditRecorder;
import com.hms.bedmanagement.api.BedAllocation;
import com.hms.bedmanagement.api.BedAllocation.AssignmentView;
import com.hms.billing.api.ChargeSource;
import com.hms.billing.api.ServicePrice;
import com.hms.billing.api.ServicePriceLookup;
import com.hms.organization.api.OrganizationDirectory;
import com.hms.organization.api.OrganizationSummary;
import com.hms.shared.domain.BusinessRuleException;
import com.hms.shared.domain.Money;
import com.hms.shared.tenancy.OrganizationPrincipal;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admission's implementation of billing's {@link ChargeSource} port ([E8-5]) plus the charges preview. Bed-days
 * follow {@link BedDayCalculator} (BR-B6 rule [A], pending pilot sign-off: review-notes Q1) in the organization's
 * time zone; each date is priced with the bed's daily-rate service valid at the start of that date.
 */
@Service
public class AdmissionChargeSource implements ChargeSource {

    /** Preview response: room days only; services already on drafts are out of scope. */
    public record ChargesPreview(Instant asOf, List<PreviewLine> lines, Money roomTotal) {
    }

    /** {@code amount} = quantity x unitPrice, half-up. */
    public record PreviewLine(String description, int quantity, Money unitPrice, Money amount, LocalDate from,
            LocalDate to) {
    }

    private final AdmissionRepository admissions;
    private final BedAllocation beds;
    private final ServicePriceLookup prices;
    private final OrganizationDirectory organizations;
    private final AuditRecorder audit;
    private final Clock clock;

    public AdmissionChargeSource(AdmissionRepository admissions, BedAllocation beds, ServicePriceLookup prices,
            OrganizationDirectory organizations, AuditRecorder audit, Clock clock) {
        this.admissions = admissions;
        this.beds = beds;
        this.prices = prices;
        this.organizations = organizations;
        this.audit = audit;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AdmissionInfo> admission(UUID organizationId, UUID admissionId) {
        return admissions.find(organizationId, admissionId).map(AdmissionChargeSource::info);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ChargeLine> roomCharges(UUID organizationId, UUID admissionId, LocalDate afterDate, Instant endAt) {
        AdmissionRow row = admissions.find(organizationId, admissionId).orElseThrow(AdmissionService::notFound);
        return lines(organizationId, row, afterDate, endAt);
    }

    /**
     * {@code GET /admissions/{id}/charges-preview}: room days up to {@code asOf} (default now), capped at the
     * discharge instant. A cancelled admission has no room charges. Audited as a view of the admission.
     */
    @Transactional
    public ChargesPreview preview(OrganizationPrincipal actor, UUID id, Instant asOf) {
        UUID org = actor.organizationId();
        AdmissionRow row = admissions.find(org, id).orElseThrow(AdmissionService::notFound);
        Instant at = (asOf == null ? clock.instant() : asOf).truncatedTo(ChronoUnit.MICROS);
        if (at.isBefore(row.admittedAt())) {
            throw AdmissionService.invalid("asOf must not be before the admission.");
        }
        audit.record(AuditEntry.of(org, actor.userId(), "ADMISSION_VIEWED", AdmissionService.ENTITY, id,
                AuditOutcome.SUCCESS));
        List<PreviewLine> out = new ArrayList<>();
        Money total = Money.ZERO;
        for (ChargeLine l : lines(org, row, null, at)) {
            Money amount = l.unitPrice().multiply(BigDecimal.valueOf(l.quantity()));
            out.add(new PreviewLine(l.description(), l.quantity(), l.unitPrice(), amount, l.from(), l.to()));
            total = total.plus(amount);
        }
        return new ChargesPreview(at, out, total);
    }

    /**
     * Shared by the port and the preview: {@code endAt} is required (422 otherwise) and capped at the discharge
     * instant (no bed is held after it); a CANCELLED admission has no room charges.
     */
    private List<ChargeLine> lines(UUID org, AdmissionRow row, LocalDate afterDate, Instant requestedEnd) {
        if (requestedEnd == null) {
            throw AdmissionService.invalid("endAt is required.");
        }
        if (AdmissionStatus.CANCELLED.name().equals(row.status())) {
            return List.of();
        }
        Instant endAt = row.dischargedAt() != null && row.dischargedAt().isBefore(requestedEnd)
                ? row.dischargedAt() : requestedEnd;
        ZoneId zone = ZoneId.of(organizations.find(org).map(OrganizationSummary::timezone)
                .orElseThrow(() -> new IllegalStateException("organization not found")));
        Map<UUID, AssignmentView> byBed = new HashMap<>();
        List<Stay> stays = new ArrayList<>();
        for (AssignmentView a : beds.history(org, row.id())) {
            byBed.put(a.bedId(), a);
            stays.add(new Stay(a.bedId(), a.assignedAt(), a.releasedAt()));
        }
        List<ChargeLine> out = new ArrayList<>();
        UUID runBed = null;
        ServicePrice runPrice = null;
        LocalDate runFrom = null;
        LocalDate runTo = null;
        for (BedDay day : BedDayCalculator.chargedDays(stays, row.admittedAt(), endAt, zone)) {
            if (afterDate != null && !day.date().isAfter(afterDate)) {
                continue;
            }
            AssignmentView bed = byBed.get(day.bedId());
            ServicePrice price = prices.priceAt(org, bed.dailyRateServiceId(),
                    day.date().atStartOfDay(zone).toInstant()).orElseThrow(() -> new BusinessRuleException(
                            "Bed " + bed.wardName() + " " + bed.bedNo() + " has no daily rate on " + day.date()
                                    + "."));
            if (runBed != null && runBed.equals(day.bedId()) && runPrice.id().equals(price.id())
                    && runTo.plusDays(1).equals(day.date())) {
                runTo = day.date();
                continue;
            }
            if (runBed != null) {
                out.add(line(byBed.get(runBed), runPrice, runFrom, runTo));
            }
            runBed = day.bedId();
            runPrice = price;
            runFrom = day.date();
            runTo = day.date();
        }
        if (runBed != null) {
            out.add(line(byBed.get(runBed), runPrice, runFrom, runTo));
        }
        return out;
    }

    /** {@code Room charges — <ward> <bedNo> (<from>–<to>)} (ruling R3), dates ISO. */
    private static ChargeLine line(AssignmentView bed, ServicePrice price, LocalDate from, LocalDate to) {
        int days = (int) ChronoUnit.DAYS.between(from, to) + 1;
        return new ChargeLine(bed.dailyRateServiceId(), "Room charges — " + bed.wardName() + " " + bed.bedNo()
                + " (" + from + "–" + to + ")", days, price.amount(), from, to);
    }

    private static AdmissionInfo info(AdmissionRow r) {
        Instant end = r.dischargedAt() != null ? r.dischargedAt() : r.plannedDischargeAt();
        return new AdmissionInfo(r.id(), r.patientId(), r.status(), r.admittedAt(), end);
    }
}
