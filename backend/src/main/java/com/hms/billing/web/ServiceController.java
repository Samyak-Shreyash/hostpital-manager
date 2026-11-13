package com.hms.billing.web;

import com.hms.billing.application.PriceView;
import com.hms.billing.application.ServiceCatalogService;
import com.hms.billing.application.ServiceView;
import com.hms.shared.PageResult;
import com.hms.shared.tenancy.OrganizationPrincipal;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Service catalog (api-design.md section 4). No DELETE: retire with PATCH {@code active=false}; prices append-only. */
@RestController
@RequestMapping("/api/v1/services")
class ServiceController {

    private final ServiceCatalogService catalog;

    ServiceController(ServiceCatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('catalog:read')")
    PageResult<ServiceView> list(@AuthenticationPrincipal OrganizationPrincipal actor,
            @RequestParam(required = false) Boolean active, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return catalog.list(actor, active, page, size);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('catalog:read')")
    ServiceView get(@AuthenticationPrincipal OrganizationPrincipal actor, @PathVariable UUID id) {
        return catalog.get(actor, id);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('catalog:manage')")
    @ResponseStatus(HttpStatus.CREATED)
    ServiceView create(@AuthenticationPrincipal OrganizationPrincipal actor,
            @Valid @RequestBody CreateServiceRequest request) {
        return catalog.create(actor, request.code(), request.name());
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('catalog:manage')")
    ServiceView update(@AuthenticationPrincipal OrganizationPrincipal actor, @PathVariable UUID id,
            @Valid @RequestBody UpdateServiceRequest request) {
        return catalog.update(actor, id, request.version(), request.name(), request.active());
    }

    @GetMapping("/{id}/prices")
    @PreAuthorize("hasAuthority('catalog:read')")
    PageResult<PriceView> prices(@AuthenticationPrincipal OrganizationPrincipal actor, @PathVariable UUID id,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        return catalog.listPrices(actor, id, page, size);
    }

    @PostMapping("/{id}/prices")
    @PreAuthorize("hasAuthority('catalog:manage')")
    @ResponseStatus(HttpStatus.CREATED)
    PriceView addPrice(@AuthenticationPrincipal OrganizationPrincipal actor, @PathVariable UUID id,
            @Valid @RequestBody AddPriceRequest request) {
        return catalog.addPrice(actor, id, request.amount(), request.effectiveFrom());
    }
}
