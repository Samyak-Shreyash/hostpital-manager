package com.hms.payment.web;

import com.hms.payment.application.RefundService;
import com.hms.payment.application.RefundView;
import com.hms.shared.domain.Money;
import com.hms.shared.tenancy.OrganizationPrincipal;
import com.hms.shared.web.idempotency.IdempotencyService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Refunds (api-design.md section 12, [E7-4]). */
@RestController
class RefundController {

    record CreateRequest(Money amount, String reason, String mode) {
    }

    record RejectRequest(String reason) {
    }

    private final RefundService refunds;

    RefundController(RefundService refunds) {
        this.refunds = refunds;
    }

    /** Request a refund of a payment; Idempotency-Key required, a replay returns the stored response. */
    @PostMapping("/api/v1/payments/{id}/refunds")
    @PreAuthorize("hasAuthority('refund:create')")
    ResponseEntity<RefundView> create(@AuthenticationPrincipal OrganizationPrincipal actor, @PathVariable UUID id,
            @RequestBody CreateRequest request,
            @RequestHeader(name = IdempotencyService.HEADER, required = false) String idempotencyKey) {
        IdempotencyService.Result<RefundView> result = refunds.create(actor, id, request.amount(), request.mode(),
                request.reason(), idempotencyKey,
                RefundService.createRequestHash(id, request.amount(), request.mode(), request.reason()));
        return ResponseEntity.status(result.status()).body(result.body());
    }

    @PostMapping("/api/v1/refunds/{id}/approve")
    @PreAuthorize("hasAuthority('refund:approve')")
    RefundView approve(@AuthenticationPrincipal OrganizationPrincipal actor, @PathVariable UUID id) {
        return refunds.approve(actor, id);
    }

    @PostMapping("/api/v1/refunds/{id}/reject")
    @PreAuthorize("hasAuthority('refund:approve')")
    RefundView reject(@AuthenticationPrincipal OrganizationPrincipal actor, @PathVariable UUID id,
            @RequestBody RejectRequest request) {
        return refunds.reject(actor, id, request.reason());
    }
}
