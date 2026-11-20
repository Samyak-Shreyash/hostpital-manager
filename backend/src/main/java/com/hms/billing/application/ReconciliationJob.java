package com.hms.billing.application;

import com.hms.audit.api.AuditEntry;
import com.hms.audit.api.AuditOutcome;
import com.hms.audit.api.AuditRecorder;
import com.hms.billing.domain.Reconciliation;
import com.hms.billing.infrastructure.ReconciliationRepository;
import com.hms.organization.api.OrganizationDirectory;
import com.hms.shared.tenancy.OrganizationContext;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Nightly billing reconciliation ([E7-7], ADR-0012): per organization, flags invoices whose status or money
 * disagree (see {@link Reconciliation}). Runs without a user session, so each organization is processed with
 * {@link OrganizationContext#runAs} in its own transaction under the normal {@code hms_app} role and RLS; one
 * failing organization does not stop the others. A mismatch logs ERROR (invoice and organization ids only), bumps
 * {@code hms.billing.reconciliation.mismatch} (Prometheus: {@code hms_billing_reconciliation_mismatch_total}) and
 * writes a {@code RECONCILIATION_MISMATCH} audit event with a null (system) actor and field names only.
 * Mismatches are re-reported on every run until fixed. Disable the schedule with
 * {@code hms.billing.reconciliation-cron=-}.
 */
@Component
public class ReconciliationJob {

    static final String METRIC = "hms.billing.reconciliation.mismatch";
    static final String ACTION = "RECONCILIATION_MISMATCH";

    private static final Logger log = LoggerFactory.getLogger(ReconciliationJob.class);

    private final OrganizationDirectory organizations;
    private final OrganizationContext organizationContext;
    private final ReconciliationRepository repository;
    private final AuditRecorder audit;
    private final TransactionTemplate transactions;
    private final Counter mismatches;

    public ReconciliationJob(OrganizationDirectory organizations, OrganizationContext organizationContext,
            ReconciliationRepository repository, AuditRecorder audit, PlatformTransactionManager transactionManager,
            MeterRegistry meters) {
        this.organizations = organizations;
        this.organizationContext = organizationContext;
        this.repository = repository;
        this.audit = audit;
        this.transactions = new TransactionTemplate(transactionManager);
        this.mismatches = Counter.builder(METRIC)
                .description("Invoices whose status and recorded money disagree, found by the nightly reconciliation")
                .register(meters);
    }

    /** All active organizations; returns the total number of mismatches found. */
    @Scheduled(cron = "${hms.billing.reconciliation-cron:0 30 2 * * *}", zone = "Asia/Kolkata")
    public int run() {
        int found = 0;
        for (UUID org : organizations.activeOrganizationIds()) {
            try {
                found += reconcile(org);
            } catch (RuntimeException e) {
                log.error("Billing reconciliation failed for organization {}", org, e);
            }
        }
        return found;
    }

    /** One organization, in one transaction; returns the number of mismatching invoices. */
    public int reconcile(UUID org) {
        Integer found = organizationContext.runAs(org, () -> transactions.execute(status -> {
            List<Reconciliation.Row> rows = repository.rows(org);
            int count = 0;
            for (Reconciliation.Row row : rows) {
                List<String> fields = Reconciliation.mismatches(row);
                if (fields.isEmpty()) {
                    continue;
                }
                count++;
                log.error("Billing reconciliation mismatch: invoice {} organization {}", row.invoiceId(), org);
                mismatches.increment();
                audit.record(new AuditEntry(org, null, ACTION, "INVOICE", row.invoiceId(), AuditOutcome.SUCCESS,
                        fields));
            }
            return count;
        }));
        return found == null ? 0 : found;
    }
}
