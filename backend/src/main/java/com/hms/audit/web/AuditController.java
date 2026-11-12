package com.hms.audit.web;

import com.hms.audit.application.AuditViewService;
import com.hms.audit.application.AuditViewService.AuditEventView;
import com.hms.audit.application.AuditViewService.Query;
import com.hms.shared.PageResult;
import com.hms.shared.tenancy.OrganizationPrincipal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Audit viewer (api-design.md section 14). Read-only; the read is itself audited. */
@RestController
@RequestMapping("/api/v1/audit-events")
class AuditController {

    private final AuditViewService viewer;

    AuditController(AuditViewService viewer) {
        this.viewer = viewer;
    }

    /** Parameters arrive as text so malformed values are a 422 VALIDATION_ERROR rather than a framework 400. */
    @GetMapping
    @PreAuthorize("hasAuthority('audit:read')")
    PageResult<AuditEventView> list(@AuthenticationPrincipal OrganizationPrincipal actor,
            @RequestParam(required = false) String actorId, @RequestParam(required = false) String action,
            @RequestParam(required = false) String entityType, @RequestParam(required = false) String entityId,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "0") String page, @RequestParam(defaultValue = "50") String size) {
        return viewer.list(actor, new Query(actorId, action, entityType, entityId, from, to), page, size);
    }
}
