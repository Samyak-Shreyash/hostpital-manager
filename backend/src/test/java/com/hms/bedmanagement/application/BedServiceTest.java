package com.hms.bedmanagement.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hms.audit.api.AuditRecorder;
import com.hms.bedmanagement.infrastructure.BedRepository;
import com.hms.bedmanagement.infrastructure.BedRepository.BedRow;
import com.hms.bedmanagement.infrastructure.WardRepository;
import com.hms.shared.domain.ConflictException;
import com.hms.shared.id.UuidV7Generator;
import com.hms.shared.tenancy.OrganizationPrincipal;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** [E8-1] The occupancy seam: an occupied bed cannot be taken out of service (wired to assignments in [E8-2]). */
class BedServiceTest {

    private final UUID org = UUID.randomUUID();
    private final UUID user = UUID.randomUUID();
    private final UUID bedId = UUID.randomUUID();
    private final BedRepository beds = mock(BedRepository.class);
    private final BedOccupancy occupancy = mock(BedOccupancy.class);
    private final AuditRecorder audit = mock(AuditRecorder.class);
    private final BedService service = new BedService(beds, mock(WardRepository.class), occupancy, audit,
            mock(UuidV7Generator.class), Clock.systemUTC());
    private final OrganizationPrincipal actor = new OrganizationPrincipal() {
        public UUID organizationId() {
            return org;
        }

        public UUID userId() {
            return user;
        }
    };

    private BedRow bed(String status) {
        return new BedRow(bedId, UUID.randomUUID(), "B-1", null, UUID.randomUUID(), status, 0, Instant.now(),
                Instant.now());
    }

    @Test
    void occupiedBedCannotGoOutOfService() {
        when(beds.findForUpdate(org, bedId)).thenReturn(Optional.of(bed("ACTIVE")));
        when(occupancy.isOccupied(org, bedId)).thenReturn(true);

        assertThatThrownBy(() -> service.update(actor, bedId, 0, null, null, "OUT_OF_SERVICE"))
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.code()).isEqualTo("BED_OCCUPIED"));
        verify(beds, never()).update(any(), any(), anyLong(), any(), any(), any(), any());
    }

    @Test
    void freeBedGoesOutOfService() {
        when(beds.findForUpdate(org, bedId)).thenReturn(Optional.of(bed("ACTIVE")));
        when(beds.find(org, bedId)).thenReturn(Optional.of(bed("OUT_OF_SERVICE")));
        when(beds.update(any(), any(), anyLong(), any(), any(), any(), any())).thenReturn(true);
        when(occupancy.isOccupied(org, bedId)).thenReturn(false);

        assertThat(service.update(actor, bedId, 0, null, null, "OUT_OF_SERVICE").status())
                .isEqualTo("OUT_OF_SERVICE");
    }

    @Test
    void occupiedBedMayStillChangeClass() {
        when(beds.findForUpdate(org, bedId)).thenReturn(Optional.of(bed("ACTIVE")));
        when(beds.find(org, bedId)).thenReturn(Optional.of(bed("ACTIVE")));
        when(beds.update(any(), any(), anyLong(), any(), any(), any(), any())).thenReturn(true);
        when(occupancy.isOccupied(org, bedId)).thenReturn(true);

        assertThat(service.update(actor, bedId, 0, "PRIVATE", null, null)).isNotNull();
    }
}
