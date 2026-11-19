package com.hms.appointment.domain;

import static com.hms.appointment.domain.AppointmentStatus.CANCELLED;
import static com.hms.appointment.domain.AppointmentStatus.CHECKED_IN;
import static com.hms.appointment.domain.AppointmentStatus.COMPLETED;
import static com.hms.appointment.domain.AppointmentStatus.IN_CONSULTATION;
import static com.hms.appointment.domain.AppointmentStatus.NO_SHOW;
import static com.hms.appointment.domain.AppointmentStatus.RESCHEDULED;
import static com.hms.appointment.domain.AppointmentStatus.SCHEDULED;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** domain-model.md section 6: every (from, to) pair, allowed ones listed, all others refused. */
class AppointmentStatusTest {

    private static final Map<AppointmentStatus, Set<AppointmentStatus>> ALLOWED = Map.of(
            SCHEDULED, Set.of(CHECKED_IN, CANCELLED, NO_SHOW, RESCHEDULED),
            CHECKED_IN, Set.of(IN_CONSULTATION, CANCELLED),
            IN_CONSULTATION, Set.of(COMPLETED),
            COMPLETED, Set.of(),
            CANCELLED, Set.of(),
            NO_SHOW, Set.of(),
            RESCHEDULED, Set.of());

    @Test
    void everyPairFollowsTheLifecycle() {
        assertThat(AppointmentStatus.values()).hasSize(7);
        for (AppointmentStatus from : AppointmentStatus.values()) {
            for (AppointmentStatus to : AppointmentStatus.values()) {
                assertThat(from.canTransitionTo(to)).as("%s -> %s", from, to)
                        .isEqualTo(ALLOWED.get(from).contains(to));
            }
        }
    }

    @Test
    void terminalStatesAreTheOnesWithoutSuccessors() {
        assertThat(COMPLETED.isTerminal()).isTrue();
        assertThat(CANCELLED.isTerminal()).isTrue();
        assertThat(NO_SHOW.isTerminal()).isTrue();
        assertThat(RESCHEDULED.isTerminal()).isTrue();
        assertThat(SCHEDULED.isTerminal()).isFalse();
    }
}
