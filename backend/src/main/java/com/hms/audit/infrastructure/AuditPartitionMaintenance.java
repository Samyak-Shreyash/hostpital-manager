package com.hms.audit.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps monthly {@code audit_event} partitions ahead of time (ADR-0007) through
 * {@code hms.ensure_audit_event_partitions}, which the app role may execute without DDL rights.
 * Runs at startup and daily. There is no default partition, so if this stops running, audit inserts
 * (and the business writes they belong to) fail once the horizon is reached.
 */
@Component
public class AuditPartitionMaintenance {

    private static final Logger log = LoggerFactory.getLogger(AuditPartitionMaintenance.class);

    private final JdbcClient jdbc;
    private final int monthsAhead;

    public AuditPartitionMaintenance(JdbcClient jdbc,
            @Value("${hms.audit.partition-months-ahead:3}") int monthsAhead) {
        this.jdbc = jdbc;
        this.monthsAhead = monthsAhead;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Scheduled(cron = "${hms.audit.partition-cron:0 15 2 * * *}", zone = "UTC")
    public int ensurePartitions() {
        try {
            int created = jdbc.sql("SELECT hms.ensure_audit_event_partitions(:monthsAhead)")
                    .param("monthsAhead", monthsAhead)
                    .query(Integer.class)
                    .single();
            if (created > 0) {
                log.info("Created {} audit_event partition(s)", created);
            }
            return created;
        } catch (RuntimeException e) {
            log.error("audit_event partition maintenance failed; audit writes fail when partitions run out", e);
            return 0;
        }
    }
}
