package com.hms.shared.tenancy;

import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionExecution;
import org.springframework.transaction.TransactionExecutionListener;

/**
 * Sets {@code app.organization_id} as the first statement of every new transaction that has an organization
 * context (ADR-0010 spike outcome). Spring Boot registers {@link TransactionExecutionListener} beans on the
 * transaction manager, so this covers {@code @Transactional} services and {@code TransactionTemplate} alike.
 * Transactions without a context (e.g. jobs) set nothing and see no rows of RLS tables.
 */
@Component
class OrganizationTransactionListener implements TransactionExecutionListener {

    /**
     * Resolving the principal can itself start a transaction: the security context is loaded lazily from the
     * JDBC session store. That nested transaction only touches spring_session (outside RLS), so it is skipped.
     */
    private static final ThreadLocal<Boolean> RESOLVING = ThreadLocal.withInitial(() -> false);

    private final OrganizationContext context;

    OrganizationTransactionListener(OrganizationContext context) {
        this.context = context;
    }

    @Override
    public void afterBegin(TransactionExecution transaction, Throwable beginFailure) {
        if (beginFailure != null || RESOLVING.get()) {
            return;
        }
        RESOLVING.set(true);
        try {
            context.current().ifPresent(context::apply);
        } finally {
            RESOLVING.remove();
        }
    }
}
