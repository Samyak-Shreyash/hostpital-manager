package com.hms.seed;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.core.env.Environment;

/**
 * The environment check of the seed generator ([E2-4]): it runs only in {@code dev} or {@code staging}, never in
 * production. The environment is the active Spring profiles, or the {@code HMS_ENV} variable (the repository has no
 * {@code staging} profile; a staging host sets {@code HMS_ENV=staging}). A production marker anywhere wins, and an
 * unknown {@code HMS_ENV} value is refused rather than guessed.
 */
public final class SeedGuard {

    static final Set<String> ALLOWED = Set.of("dev", "staging");
    static final Set<String> PRODUCTION = Set.of("prod", "production");

    private SeedGuard() {
    }

    public static void check(Environment environment) {
        check(List.of(environment.getActiveProfiles()), environment.getProperty("HMS_ENV"));
    }

    /** @throws SeedRefusedException unless the environment is dev or staging */
    public static void check(Collection<String> activeProfiles, String hmsEnv) {
        List<String> profiles = activeProfiles.stream().map(p -> p.trim().toLowerCase(Locale.ROOT)).toList();
        String env = hmsEnv == null ? "" : hmsEnv.trim().toLowerCase(Locale.ROOT);
        if (profiles.stream().anyMatch(PRODUCTION::contains) || PRODUCTION.contains(env)) {
            throw new SeedRefusedException("Seeding is never allowed in production.");
        }
        if (!env.isEmpty() && !ALLOWED.contains(env)) {
            throw new SeedRefusedException("HMS_ENV must be dev or staging to seed.");
        }
        if (profiles.stream().noneMatch(ALLOWED::contains) && env.isEmpty()) {
            throw new SeedRefusedException(
                    "Seeding needs the dev profile, the staging profile or HMS_ENV=dev|staging.");
        }
    }

    /** Raised when the guard refuses; the message never contains configuration values. */
    public static final class SeedRefusedException extends IllegalStateException {

        public SeedRefusedException(String message) {
            super(message);
        }
    }
}
