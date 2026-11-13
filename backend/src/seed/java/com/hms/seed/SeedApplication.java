package com.hms.seed;

import com.hms.HmsApplication;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.core.env.StandardEnvironment;

/**
 * Entry point of {@code ./gradlew seedData} ([E2-4]). The environment guard runs before the application context (and
 * so before any database connection) is created; {@link SeedService} checks it again against the real environment.
 */
public final class SeedApplication {

    private SeedApplication() {
    }

    public static void main(String[] args) {
        StandardEnvironment early = new StandardEnvironment();
        String profiles = early.getProperty("spring.profiles.active", "");
        SeedGuard.check(profiles.isBlank() ? List.of() : Arrays.asList(profiles.split(",")),
                early.getProperty("HMS_ENV"));
        SeedOptions.parse(List.of(args)); // reject bad arguments before starting anything
        new SpringApplicationBuilder(HmsApplication.class)
                .web(WebApplicationType.SERVLET) // the security configuration needs the web stack; a random port is bound while it runs
                .properties(Map.of("hms.seed.enabled", "true", "hms.seed.run", "true", "spring.main.banner-mode", "off",
                        "server.port", "0", "management.endpoints.enabled-by-default", "false"))
                .run(args)
                .close();
    }
}
