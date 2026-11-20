package com.hms.billing.web;

import com.hms.billing.application.CreditNoteService;
import com.hms.billing.application.CreditNoteView;
import com.hms.shared.domain.Money;
import com.hms.shared.tenancy.OrganizationPrincipal;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Credit notes (api-design.md section 12, [E7-4]). */
@RestController
class CreditNoteController {

    record CreateRequest(Money amount, String reason) {
    }

    private final CreditNoteService creditNotes;

    CreditNoteController(CreditNoteService creditNotes) {
        this.creditNotes = creditNotes;
    }

    @PostMapping("/api/v1/invoices/{id}/credit-notes")
    @PreAuthorize("hasAuthority('invoice:credit')")
    @ResponseStatus(HttpStatus.CREATED)
    CreditNoteView create(@AuthenticationPrincipal OrganizationPrincipal actor, @PathVariable UUID id,
            @RequestBody CreateRequest request) {
        return creditNotes.issue(actor, id, request.amount(), request.reason());
    }
}
