package com.hms.admission.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hms.admission.infrastructure.AdmissionRepository;
import com.hms.admission.infrastructure.AdmissionRepository.AdmissionRow;
import com.hms.audit.api.AuditRecorder;
import com.hms.bedmanagement.api.BedAllocation;
import com.hms.bedmanagement.api.BedAllocation.AssignmentView;
import com.hms.clinical.api.EncounterQuery;
import com.hms.organization.api.OrganizationDirectory;
import com.hms.patient.api.PatientDirectory;
import com.hms.practitioner.api.PractitionerAvailability;
import com.hms.practitioner.api.PractitionerDirectory;
import com.hms.shared.domain.ConflictException;
import com.hms.shared.id.UuidV7Generator;
import com.hms.shared.numbering.DocumentNumberAllocator;
import com.hms.shared.tenancy.OrganizationPrincipal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** [E8-4] The lock-then-recheck retry of {@code underBedLock}, driven through transfer. Synthetic ids only. */
class AdmissionServiceRetryTest {

    private final UUID org = UUID.randomUUID();
    private final UUID id = UUID.randomUUID();
    private final UUID fromBed = UUID.randomUUID();
    private final UUID toBed = UUID.randomUUID();

    private AdmissionRepository admissions;
    private BedAllocation beds;
    private PlatformTransactionManager tm;
    private AdmissionService service;
    private OrganizationPrincipal actor;

    @BeforeEach
    void setUp() {
        admissions = mock(AdmissionRepository.class);
        beds = mock(BedAllocation.class);
        tm = mock(PlatformTransactionManager.class);
        when(tm.getTransaction(any())).thenAnswer(i -> new SimpleTransactionStatus());
        service = new AdmissionService(admissions, beds, mock(PatientDirectory.class),
                mock(PractitionerDirectory.class), mock(PractitionerAvailability.class), mock(EncounterQuery.class),
                mock(OrganizationDirectory.class), mock(DocumentNumberAllocator.class), mock(AuditRecorder.class),
                mock(UuidV7Generator.class), Clock.fixed(Instant.parse("2026-11-21T00:00:00Z"), ZoneOffset.UTC), tm,
                mock(com.hms.billing.api.InvoiceLookup.class));
        actor = mock(OrganizationPrincipal.class);
        when(actor.organizationId()).thenReturn(org);
        when(actor.userId()).thenReturn(UUID.randomUUID());
        AdmissionRow row = new AdmissionRow(id, "ADM-2026-0001", UUID.randomUUID(), UUID.randomUUID(), null,
                "Synthetic", "ADMITTED", Instant.EPOCH, null, null, 0, Instant.EPOCH, Instant.EPOCH);
        when(admissions.find(org, id)).thenReturn(Optional.of(row));
        when(admissions.lock(org, id)).thenReturn(Optional.of(row));
    }

    @AfterEach
    void clear() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    private Map<UUID, AssignmentView> held(UUID assignmentId) {
        return Map.of(id, new AssignmentView(assignmentId, fromBed, "B-1", "Ward", null, Instant.EPOCH, null, null));
    }

    @Test
    void oneStaleReadIsRetriedInAFreshTransactionAndSucceeds() {
        UUID a1 = UUID.randomUUID();
        UUID a2 = UUID.randomUUID();
        when(beds.current(eq(org), anyList())).thenReturn(held(a1), held(a2), held(a2), held(a2));
        AdmissionService.TransferView result = service.transfer(actor, id, toBed, "Synthetic reason");
        assertThat(result).isNotNull();
        verify(tm, times(2)).getTransaction(any());
        verify(tm, times(1)).rollback(any());
        verify(tm, times(1)).commit(any());
        verify(beds, times(1)).release(eq(org), eq(id), any());
    }

    @Test
    void twoStaleReadsAre409ConcurrentModification() {
        when(beds.current(eq(org), anyList())).thenReturn(held(UUID.randomUUID()), held(UUID.randomUUID()),
                held(UUID.randomUUID()), held(UUID.randomUUID()));
        assertThatThrownBy(() -> service.transfer(actor, id, toBed, "Synthetic reason"))
                .isInstanceOf(ConflictException.class)
                .extracting("code").isEqualTo("CONCURRENT_MODIFICATION");
        verify(tm, times(2)).getTransaction(any());
        verify(tm, times(2)).rollback(any());
        verify(tm, never()).commit(any());
        verify(beds, never()).release(any(), any(), any());
    }

    @Test
    void refusesToRunInsideAnOpenTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        assertThatThrownBy(() -> service.transfer(actor, id, toBed, "Synthetic reason"))
                .isInstanceOf(IllegalStateException.class);
        verify(tm, never()).getTransaction(any());
    }
}
