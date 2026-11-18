package com.hms.patient;

import static org.assertj.core.api.Assertions.assertThat;

import com.hms.support.PostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Name search must use the trigram index under row-level security (ADR-0010). Postgres applies a non-LEAKPROOF
 * operator only after the RLS policy, which turns the search into a full table scan, so the superuser database init
 * step marks the pg_trgm operator function LEAKPROOF. This fails if a database is set up without that step.
 */
@SpringBootTest
class PatientSearchIndexIT extends PostgresIT {

    @Autowired
    JdbcClient jdbc;

    @Test
    void wordSimilarityOperatorIsLeakproof() {
        Boolean leakproof = jdbc.sql("""
                SELECT proleakproof FROM pg_proc
                WHERE oid = 'hms.word_similarity_commutator_op(text, text)'::regprocedure
                """).query(Boolean.class).single();
        assertThat(leakproof).isTrue();
    }
}
