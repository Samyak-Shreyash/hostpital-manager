package com.hms.appointment.web;

import com.hms.appointment.application.QueueService;
import com.hms.appointment.application.QueueService.QueueView;
import com.hms.shared.tenancy.OrganizationPrincipal;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The live OPD queue (api-design.md section 7, [E5-3]). */
@RestController
@RequestMapping("/api/v1/queue")
class QueueController {

    private final QueueService queue;

    QueueController(QueueService queue) {
        this.queue = queue;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('appointment:read')")
    QueueView queue(@AuthenticationPrincipal OrganizationPrincipal actor,
            @RequestParam(required = false) UUID doctorId, @RequestParam(required = false) LocalDate date) {
        return queue.queue(actor, doctorId, date);
    }
}
