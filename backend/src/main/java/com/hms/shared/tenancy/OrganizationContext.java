package com.hms.shared.tenancy;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The organization the current work runs in (ADR-0010). It is the authenticated principal's organization, never a
 * value from request input. System and unauthenticated flows (login, bootstrap, jobs) name the organization
 * explicitly with {@link #runAs} or {@link #bindToCurrentTransaction}.
 *
 * <p>{@link OrganizationTransactionListener} writes the value into the transaction-local setting
 * {@code app.organization_id} at the start of every transaction, which the RLS policies read. Without an
 * organization nothing is set, and RLS tables return no rows (fail-closed).
 */
@Component
public class OrganizationContext {

    private static final ThreadLocal<UUID> EXPLICIT = new ThreadLocal<>();

    private final JdbcClient jdbc;

    public OrganizationContext(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The explicit organization of {@link #runAs}, otherwise the authenticated principal's. */
    public Optional<UUID> current() {
        UUID explicit = EXPLICIT.get();
        return explicit != null ? Optional.of(explicit) : principalOrganization();
    }

    /** The authenticated principal's organization; fails if there is none (use only behind authentication). */
    public UUID require() {
        return current().orElseThrow(() -> new IllegalStateException("no organization context"));
    }

    /**
     * Runs {@code work} in {@code organizationId} for system or unauthenticated flows. Transactions started inside
     * use it; a transaction already active is switched for the duration and switched back afterwards.
     * Refuses to switch an authenticated user into another organization.
     */
    public <T> T runAs(UUID organizationId, Supplier<T> work) {
        Objects.requireNonNull(organizationId, "organizationId");
        rejectForeignOrganization(organizationId);
        UUID previous = EXPLICIT.get();
        Optional<UUID> before = current();
        EXPLICIT.set(organizationId);
        try {
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                apply(organizationId);
            }
            return work.get();
        } finally {
            if (previous == null) {
                EXPLICIT.remove();
            } else {
                EXPLICIT.set(previous);
            }
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                apply(before.orElse(null));
            }
        }
    }

    public void runAs(UUID organizationId, Runnable work) {
        runAs(organizationId, () -> {
            work.run();
            return null;
        });
    }

    /**
     * Sets the organization for the rest of the active transaction, for flows that learn it mid-transaction
     * (login after the email lookup, provisioning a new organization). Refuses another org than the principal's.
     */
    public void bindToCurrentTransaction(UUID organizationId) {
        Objects.requireNonNull(organizationId, "organizationId");
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("bindToCurrentTransaction needs an active transaction");
        }
        rejectForeignOrganization(organizationId);
        apply(organizationId);
    }

    /** Transaction-local only ({@code is_local = true}): a session-level value would leak to the next borrower. */
    void apply(UUID organizationId) {
        jdbc.sql("SELECT set_config('app.organization_id', ?, true)")
                .param(organizationId == null ? "" : organizationId.toString())
                .query()
                .singleValue();
    }

    private void rejectForeignOrganization(UUID organizationId) {
        principalOrganization().filter(own -> !own.equals(organizationId)).ifPresent(own -> {
            throw new IllegalStateException("an authenticated user cannot act in another organization");
        });
    }

    private static Optional<UUID> principalOrganization() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof OrganizationPrincipal principal) {
            return Optional.of(principal.organizationId());
        }
        return Optional.empty();
    }
}
