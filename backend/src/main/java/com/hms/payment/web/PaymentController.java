package com.hms.payment.web;

import com.hms.payment.application.PaymentService;
import com.hms.payment.application.PaymentView;
import com.hms.payment.application.RecordedPayment;
import com.hms.shared.PageResult;
import com.hms.shared.domain.Money;
import com.hms.shared.tenancy.OrganizationPrincipal;
import com.hms.shared.web.idempotency.IdempotencyService;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Payments (api-design.md section 12, [E7-3]). */
@RestController
class PaymentController {

    record CreateRequest(Money amount, String mode, String referenceNo) {
    }

    private final PaymentService payments;

    PaymentController(PaymentService payments) {
        this.payments = payments;
    }

    /** Record a payment on an issued invoice; Idempotency-Key required, a replay returns the stored response. */
    @PostMapping("/api/v1/invoices/{id}/payments")
    @PreAuthorize("hasAuthority('payment:create')")
    ResponseEntity<RecordedPayment> create(@AuthenticationPrincipal OrganizationPrincipal actor,
            @PathVariable UUID id, @RequestBody CreateRequest request,
            @RequestHeader(name = IdempotencyService.HEADER, required = false) String idempotencyKey) {
        IdempotencyService.Result<RecordedPayment> result = payments.record(actor, id, request.amount(),
                request.mode(), request.referenceNo(), idempotencyKey,
                PaymentService.createRequestHash(id, request.amount(), request.mode(), request.referenceNo()));
        return ResponseEntity.status(result.status()).body(result.body());
    }

    /** [E7-5] The receipt PDF of a payment. */
    @GetMapping(value = "/api/v1/payments/{id}/receipt", produces = MediaType.APPLICATION_PDF_VALUE)
    @PreAuthorize("hasAuthority('payment:read')")
    ResponseEntity<byte[]> receipt(@AuthenticationPrincipal OrganizationPrincipal actor, @PathVariable UUID id) {
        PaymentService.ReceiptFile file = payments.receipt(actor, id);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(file.filename())
                        .build().toString())
                .body(file.bytes());
    }

    @GetMapping("/api/v1/payments")
    @PreAuthorize("hasAuthority('payment:read')")
    PageResult<PaymentView> list(@AuthenticationPrincipal OrganizationPrincipal actor,
            @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to,
            @RequestParam(required = false) String mode, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return payments.list(actor, from, to, mode, page, size);
    }
}
