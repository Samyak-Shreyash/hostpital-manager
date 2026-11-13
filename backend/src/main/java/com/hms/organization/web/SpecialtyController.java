package com.hms.organization.web;

import com.hms.organization.application.CatalogItemView;
import com.hms.organization.application.CatalogKind;
import com.hms.organization.application.CatalogService;
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

/** Specialties (api-design.md section 4). No DELETE: deactivate with PATCH {@code active=false}. */
@RestController
@RequestMapping("/api/v1/specialties")
class SpecialtyController {

    private static final CatalogKind KIND = CatalogKind.SPECIALTY;

    private final CatalogService catalog;

    SpecialtyController(CatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('catalog:read')")
    PageResult<CatalogItemView> list(@AuthenticationPrincipal OrganizationPrincipal actor,
            @RequestParam(required = false) Boolean active, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return catalog.list(actor, KIND, active, page, size);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('catalog:manage')")
    @ResponseStatus(HttpStatus.CREATED)
    CatalogItemView create(@AuthenticationPrincipal OrganizationPrincipal actor,
            @Valid @RequestBody CreateSpecialtyRequest request) {
        return catalog.create(actor, KIND, request.code(), request.name());
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('catalog:manage')")
    CatalogItemView update(@AuthenticationPrincipal OrganizationPrincipal actor, @PathVariable UUID id,
            @Valid @RequestBody UpdateSpecialtyRequest request) {
        return catalog.update(actor, KIND, id, request.version(), request.name(), request.active());
    }
}
