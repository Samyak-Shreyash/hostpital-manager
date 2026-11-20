package com.hms.billing.web;

import com.hms.billing.application.InvoicePdfService;
import com.hms.billing.application.InvoiceService;
import com.hms.billing.application.InvoiceService.ItemInput;
import com.hms.billing.application.InvoiceView;
import com.hms.identity.api.Permissions;
import com.hms.shared.PageResult;
import com.hms.shared.tenancy.OrganizationPrincipal;
import com.hms.shared.web.idempotency.IdempotencyService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Invoices (api-design.md section 12). [E7-1]: OPD drafts, list, read, replace items, discard; [E7-2] issue. */
@RestController
@RequestMapping("/api/v1/invoices")
class InvoiceController {

    record CreateRequest(String type, UUID patientId, UUID encounterId) {
    }

    record ItemsRequest(Long version, List<ItemInput> items) {
    }

    record DiscountRequest(Long version, String percent, String reason) {
    }

    record VersionRequest(Long version) {
    }

    private final InvoiceService invoices;
    private final InvoicePdfService pdfs;

    InvoiceController(InvoiceService invoices, InvoicePdfService pdfs) {
        this.invoices = invoices;
        this.pdfs = pdfs;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('invoice:create')")
    @ResponseStatus(HttpStatus.CREATED)
    InvoiceView create(@AuthenticationPrincipal OrganizationPrincipal actor, @RequestBody CreateRequest request) {
        return invoices.createDraft(actor, request.type(), request.patientId(), request.encounterId());
    }

    @GetMapping
    @PreAuthorize("hasAuthority('invoice:read')")
    PageResult<InvoiceView> list(@AuthenticationPrincipal OrganizationPrincipal actor,
            @RequestParam(required = false) UUID patientId, @RequestParam(required = false) String status,
            @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        return invoices.list(actor, patientId, status, from, to, page, size);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('invoice:read')")
    InvoiceView get(@AuthenticationPrincipal OrganizationPrincipal actor, @PathVariable UUID id) {
        return invoices.get(actor, id);
    }

    /** [E7-5] The printable invoice; {@code produces} is declared so a client asking for a PDF is routed here. */
    @GetMapping(value = "/{id}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    @PreAuthorize("hasAuthority('invoice:read')")
    ResponseEntity<byte[]> pdf(@AuthenticationPrincipal OrganizationPrincipal actor, @PathVariable UUID id) {
        InvoicePdfService.PdfFile file = pdfs.pdf(actor, id);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(file.filename())
                        .build().toString())
                .body(file.bytes());
    }

    @PutMapping("/{id}/items")
    @PreAuthorize("hasAuthority('invoice:update')")
    InvoiceView items(@AuthenticationPrincipal OrganizationPrincipal actor, Authentication authentication,
            @PathVariable UUID id, @RequestBody ItemsRequest request) {
        boolean mayOverride = authentication.getAuthorities().stream()
                .anyMatch(a -> Permissions.INVOICE_PRICE_OVERRIDE.equals(a.getAuthority()));
        boolean mayDiscount = authentication.getAuthorities().stream()
                .anyMatch(a -> Permissions.INVOICE_DISCOUNT.equals(a.getAuthority()));
        return invoices.replaceItems(actor, id, request.version(), request.items(), mayOverride, mayDiscount);
    }

    @PutMapping("/{id}/discount")
    @PreAuthorize("hasAuthority('invoice:discount')")
    InvoiceView discount(@AuthenticationPrincipal OrganizationPrincipal actor, @PathVariable UUID id,
            @RequestBody DiscountRequest request) {
        return invoices.applyPercentDiscount(actor, id, request.version(), request.percent(), request.reason());
    }

    /** [E7-2] Issue a DRAFT; Idempotency-Key required, a replay returns the stored response. */
    @PostMapping("/{id}/issue")
    @PreAuthorize("hasAuthority('invoice:issue')")
    ResponseEntity<InvoiceView> issue(@AuthenticationPrincipal OrganizationPrincipal actor, @PathVariable UUID id,
            @RequestHeader(name = IdempotencyService.HEADER, required = false) String idempotencyKey) {
        IdempotencyService.Result<InvoiceView> result = invoices.issue(actor, id, idempotencyKey,
                InvoiceService.issueRequestHash(id));
        return ResponseEntity.status(result.status()).body(result.body());
    }

    @PostMapping("/{id}/discard")
    @PreAuthorize("hasAuthority('invoice:update')")
    InvoiceView discard(@AuthenticationPrincipal OrganizationPrincipal actor, @PathVariable UUID id,
            @RequestBody VersionRequest request) {
        return invoices.discard(actor, id, request.version());
    }
}
