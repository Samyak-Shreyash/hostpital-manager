package com.hms.seed;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Runs the seed at startup of {@link SeedApplication} only ({@code hms.seed.run=true}); never in tests or the app. */
@Component
@ConditionalOnProperty(name = {"hms.seed.enabled", "hms.seed.run"}, havingValue = "true")
class SeedRunner implements ApplicationRunner {

    private final SeedService seed;

    SeedRunner(SeedService seed) {
        this.seed = seed;
    }

    @Override
    public void run(ApplicationArguments args) {
        seed.run(SeedOptions.parse(java.util.List.of(args.getSourceArgs())));
    }
}
