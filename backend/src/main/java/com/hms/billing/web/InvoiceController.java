package com.hms.billing.web;

import com.hms.billing.application.InvoiceService;
import com.hms.billing.application.InvoiceService.ItemInput;
import com.hms.billing.application.InvoiceView;
import com.hms.identity.api.Permissions;
import com.hms.shared.PageResult;
import com.hms.shared.tenancy.OrganizationPrincipal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Invoices (api-design.md section 12). [E7-1]: OPD drafts, list, read, replace items, discard. */
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

    InvoiceController(InvoiceService invoices) {
        this.invoices = invoices;
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

    @PostMapping("/{id}/discard")
    @PreAuthorize("hasAuthority('invoice:update')")
    InvoiceView discard(@AuthenticationPrincipal OrganizationPrincipal actor, @PathVariable UUID id,
            @RequestBody VersionRequest request) {
        return invoices.discard(actor, id, request.version());
    }
}
