package com.hms.seed;

import java.time.LocalDate;
import java.util.List;

/**
 * Deterministic synthetic values. Nothing here resembles a real person: names combine a NATO letter word with a
 * placeholder surname under a {@code Test} prefix; phones are {@code +91 0000 xxxxxx} (an Indian number never starts
 * with 0, so these cannot be assigned to anyone); pincode {@code 000000} and the city "Testville" do not exist.
 * Everything is a pure function of the patient index, so reruns and top-ups continue the same series.
 */
final class SeedData {

    static final String SEED_ORG_NAME = "Seed Demo Clinic";
    static final String SEED_EMAIL_DOMAIN = "seed.example"; // .example is reserved (RFC 2606), never deliverable
    static final String PHONE_PREFIX = "+910000";
    static final String CONTACT_PHONE_PREFIX = "+910001";
    static final String PHONE_PATTERN = "^\\+910000[0-9]{6}$";
    static final String NAME_PATTERN = "^Test [A-Za-z]+ [A-Za-z]+$";
    static final String NAME_PREFIX = "Test ";

    static final List<String> NATO = List.of("Alpha", "Bravo", "Charlie", "Delta", "Echo", "Foxtrot", "Golf", "Hotel",
            "India", "Juliet", "Kilo", "Lima", "Mike", "November", "Oscar", "Papa", "Quebec", "Romeo", "Sierra",
            "Tango", "Uniform", "Victor", "Whiskey", "Xray", "Yankee", "Zulu", "Amber", "Birch", "Cedar", "Dune");
    static final List<String> SURNAMES = List.of("Sample", "Example", "Demo", "Mock", "Dummy", "Synthetic", "Fixture",
            "Placeholder", "Specimen", "Trial", "Pilot", "Proto", "Stub", "Fake", "Model", "Sandbox", "Testcase",
            "Draft", "Mockup", "Replica", "Lorem", "Ipsum", "Dolor", "Amet", "Nominal", "Generic", "Default",
            "Standard", "Virtual", "Phantom");
    static final List<String> ALLERGENS = List.of("Penicillin", "Peanuts", "Dust mites", "Latex", "Sulfa drugs");
    static final List<String> REACTIONS = List.of("Rash", "Hives", "Wheezing", "Swelling");
    static final List<String> SEVERITIES = List.of("MILD", "MODERATE", "SEVERE");
    static final List<String> CONDITIONS = List.of("Hypertension", "Type 2 diabetes", "Asthma", "Migraine",
            "Hypothyroidism");

    private static final LocalDate DOB_BASE = LocalDate.of(1940, 1, 1);
    private static final int DOB_SPAN_DAYS = 30_000; // up to 2022; 7919 is coprime with it: unique below 30000

    private SeedData() {
    }

    static String patientName(int i) {
        return NAME_PREFIX + NATO.get(i % NATO.size()) + " " + SURNAMES.get((i / NATO.size()) % SURNAMES.size());
    }

    /** Unique per index below 30,000, so the duplicate rule (same DOB and similar name) never fires on the service path. */
    static LocalDate dateOfBirth(int i) {
        return DOB_BASE.plusDays((long) i * 7919 % DOB_SPAN_DAYS);
    }

    static String sex(int i) {
        return switch (i % 10) {
            case 0, 1, 2, 3 -> "M";
            case 4, 5, 6, 7 -> "F";
            case 8 -> "O";
            default -> "U";
        };
    }

    static String phone(int i) {
        return PHONE_PREFIX + String.format("%06d", i);
    }

    static String contactPhone(int i) {
        return CONTACT_PHONE_PREFIX + String.format("%06d", i);
    }

    static String uhid(long number) {
        return String.format("HMS-%06d", number); // same format as PatientService
    }
}
