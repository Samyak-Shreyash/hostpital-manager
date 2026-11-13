package com.hms.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hms.seed.SeedGuard.SeedRefusedException;
import com.hms.support.PostgresIT;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/** [E2-4] Under a profile that is neither dev nor staging the seed service refuses and touches nothing. */
@SpringBootTest(properties = "hms.seed.enabled=true")
@ActiveProfiles("test")
class SeedGuardIT extends PostgresIT {

    @Autowired
    SeedService seed;

    @Autowired
    JdbcClient jdbc;

    @Test
    void refusesUnderTheTestProfileAndCreatesNothing() {
        long organizationsBefore = jdbc.sql("SELECT count(*) FROM hms.organization").query(Long.class).single();

        assertThatThrownBy(() -> seed.run(new SeedOptions(5, 5, Path.of("build", "seed-guard-it.txt"))))
                .isInstanceOf(SeedRefusedException.class);

        assertThat(jdbc.sql("SELECT count(*) FROM hms.organization").query(Long.class).single())
                .isEqualTo(organizationsBefore);
    }
}
