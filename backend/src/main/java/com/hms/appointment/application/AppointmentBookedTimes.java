package com.hms.appointment.application;

import com.hms.appointment.infrastructure.AppointmentRepository;
import com.hms.practitioner.api.BookedTimes;
import com.hms.practitioner.api.PractitionerAvailability.TimeSlot;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Tells the practitioner module which times are booked, so its free slots leave them out (Q9). */
@Service
class AppointmentBookedTimes implements BookedTimes {

    private final AppointmentRepository repository;

    AppointmentBookedTimes(AppointmentRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<TimeSlot> booked(UUID organizationId, UUID practitionerId, Instant from, Instant to) {
        return repository.booked(organizationId, practitionerId, from, to).stream()
                .map(b -> new TimeSlot(b[0], b[1])).toList();
    }
}
