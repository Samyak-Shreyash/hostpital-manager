package com.hms.shared.tenancy;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Names the transactions of the JDBC session store so {@link OrganizationTransactionListener} can skip them.
 * Loading a session starts a transaction; resolving the principal for the listener would load the session again
 * (the security context is lazy) and open a second transaction while the first still holds its connection. With
 * many concurrent first requests every pool connection ends up held by an outer transaction waiting for an inner
 * one: a pool deadlock. The session tables are outside RLS, so they need no organization setting.
 */
@Configuration(proxyBeanMethods = false)
class SessionStoreTransactions {

    /** The transaction name; {@link OrganizationTransactionListener} compares against it. */
    static final String NAME = "spring-session-store";

    /**
     * Spring Boot's own {@code TransactionTemplate} backs off once any {@link TransactionOperations} bean exists, so
     * the plain one is declared here, and primary, to keep injection of it (and of the interface) unchanged.
     */
    @Bean
    @Primary
    TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }

    /** Picked up by Spring Session under exactly this bean name. */
    @Bean
    TransactionOperations springSessionTransactionOperations(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setName(NAME);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }
}
