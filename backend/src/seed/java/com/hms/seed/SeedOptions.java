package com.hms.seed;

import java.nio.file.Path;
import java.util.List;

/**
 * What to seed. {@code patients} is the target total of seeded patients (a rerun tops up to it). {@code
 * adminPasswordFile} receives the temporary administrator password only when the seed itself bootstraps the first
 * organization. The first {@code servicePatients} patients are registered through the patient service, the rest
 * through the bulk path (see {@link SeedService}); only tests change it from the default.
 */
public record SeedOptions(int patients, int servicePatients, Path adminPasswordFile) {

    public static final int DEFAULT_PATIENTS = 2_000;
    public static final int LARGE_PATIENTS = 100_000;
    static final int MAX_PATIENTS = 900_000; // the synthetic phone range holds 1,000,000 numbers

    public SeedOptions {
        if (patients < 0 || patients > MAX_PATIENTS) {
            throw new IllegalArgumentException("patients must be between 0 and " + MAX_PATIENTS);
        }
        if (servicePatients < 0) {
            throw new IllegalArgumentException("servicePatients must not be negative");
        }
    }

    public static SeedOptions defaults() {
        return new SeedOptions(DEFAULT_PATIENTS, DEFAULT_PATIENTS, Path.of("build", "seed-admin-password.txt"));
    }

    public SeedOptions withPatients(int count) {
        return new SeedOptions(count, servicePatients, adminPasswordFile);
    }

    public SeedOptions withServicePatients(int count) {
        return new SeedOptions(patients, count, adminPasswordFile);
    }

    /** {@code --large} (100,000 patients), {@code --patients=N} and {@code --admin-password-file=PATH}. */
    public static SeedOptions parse(List<String> args) {
        SeedOptions options = defaults();
        for (String arg : args) {
            if (arg.equals("--large")) {
                options = options.withPatients(LARGE_PATIENTS);
            } else if (arg.startsWith("--patients=")) {
                try {
                    options = options.withPatients(Integer.parseInt(arg.substring("--patients=".length())));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("--patients needs a whole number");
                }
            } else if (arg.startsWith("--admin-password-file=")) {
                options = new SeedOptions(options.patients(), options.servicePatients(),
                        Path.of(arg.substring("--admin-password-file=".length())));
            } else {
                throw new IllegalArgumentException(
                        "Unknown argument (use --large, --patients=N, --admin-password-file=PATH)");
            }
        }
        return options;
    }
}
