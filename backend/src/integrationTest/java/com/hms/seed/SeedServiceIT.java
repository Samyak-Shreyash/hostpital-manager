package com.hms.seed;

import static org.assertj.core.api.Assertions.assertThat;

import com.hms.identity.api.AuthenticatedPrincipal;
import com.hms.identity.api.Permissions;
import com.hms.identity.api.RoleProvisioning;
import com.hms.identity.application.UserManagementService;
import com.hms.organization.api.OrganizationProvisioning;
import com.hms.patient.application.PatientService;
import com.hms.patient.application.PatientSummary;
import com.hms.shared.tenancy.OrganizationContext;
import com.hms.support.PostgresIT;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * [E2-4] A small seed run creates the expected rows, a rerun is a no-op, a top-up continues the numbering, and the
 * bulk path respects the UHID counter and the phone format. Runs under the dev profile (the guard allows it);
 * the 100k run is a manual exercise (README). Synthetic data only.
 */
@SpringBootTest(properties = "hms.seed.enabled=true")
@ActiveProfiles({"test", "dev"})
class SeedServiceIT extends PostgresIT {

    private static final Path PASSWORD_FILE = Path.of("build", "seed-it-password.txt");

    @Autowired
    SeedService seed;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    OrganizationContext context;

    @Autowired
    PatientService patients;

    @Autowired
    OrganizationProvisioning organizations;

    @Autowired
    RoleProvisioning roles;

    @Autowired
    UserManagementService users;

    private static UUID org;

    /**
     * The seed organization, created with the same steps as the [E2-1] bootstrap (which itself refuses when another
     * test class has already created organizations in the shared database).
     */
    private UUID seedOrganization() {
        if (org != null) {
            return org;
        }
        List<UUID> found = jdbc.sql("SELECT id FROM hms.organization WHERE name = ?").param(SeedData.SEED_ORG_NAME)
                .query(UUID.class).list();
        if (!found.isEmpty()) {
            org = found.get(0);
            return org;
        }
        org = tx.execute(status -> {
            UUID id = organizations.create(SeedData.SEED_ORG_NAME, "Asia/Kolkata");
            roles.provisionDefaultRoles(id);
            users.createInitialAdministrator(id, "seed.admin@seed.example", "System Administrator",
                    "Seed-It-Only-Password-1");
            return id;
        });
        return org;
    }

    private <T> T inOrg(Supplier<T> work) {
        Supplier<T> transactional = () -> tx.execute(status -> work.get());
        return context.runAs(seedOrganization(), transactional);
    }

    private long count(String sql) {
        return inOrg(() -> jdbc.sql(sql).query(Long.class).single());
    }

    private SeedOptions options(int patients, int viaService) {
        return new SeedOptions(patients, viaService, PASSWORD_FILE);
    }

    @Test
    void seedRunCreatesTheExpectedRowsAndARerunIsANoOp() {
        seedOrganization();

        SeedSummary first = seed.run(options(30, 10));

        assertThat(first.organizationId()).isEqualTo(org);
        assertThat(first.departments()).isEqualTo(6);
        assertThat(first.specialties()).isEqualTo(6);
        assertThat(first.services()).isEqualTo(12);
        assertThat(first.users()).isEqualTo(10);
        assertThat(first.doctors()).isEqualTo(5);
        assertThat(first.staff()).isEqualTo(5);
        assertThat(first.availabilityTemplates()).isEqualTo(5);
        assertThat(first.leaves()).isEqualTo(5);
        assertThat(first.patients()).isEqualTo(10);
        assertThat(first.bulkPatients()).isEqualTo(20);
        assertThat(first.allergies()).isGreaterThan(0);
        assertThat(first.histories()).isGreaterThan(0);

        assertThat(count("SELECT count(*) FROM hms.department")).isEqualTo(6);
        assertThat(count("SELECT count(*) FROM hms.specialty")).isEqualTo(6);
        assertThat(count("SELECT count(*) FROM hms.service_price")).isEqualTo(12);
        assertThat(count("SELECT count(*) FROM hms.practitioner")).isEqualTo(5);
        assertThat(count("SELECT count(*) FROM hms.practitioner_specialty WHERE is_primary")).isEqualTo(5);
        assertThat(count("SELECT count(*) FROM hms.staff")).isEqualTo(5);
        assertThat(count("SELECT count(*) FROM hms.availability_exception")).isEqualTo(5);
        assertThat(count("SELECT count(*) FROM hms.practitioner_availability")).isEqualTo(5 * 8);
        assertThat(count("SELECT count(*) FROM hms.patient WHERE phone LIKE '+910000%'")).isEqualTo(30);
        assertThat(count("SELECT count(*) FROM hms.patient_address")).isEqualTo(30);

        // Gapless UHIDs across the service and the bulk path, from the shared counter.
        List<String> uhids = inOrg(() -> jdbc.sql("SELECT uhid FROM hms.patient ORDER BY uhid").query(String.class).list());
        assertThat(uhids).hasSize(30).first().isEqualTo("HMS-000001");
        assertThat(uhids).last().isEqualTo("HMS-000030");
        assertThat(uhids).doesNotHaveDuplicates();
        assertThat(count("SELECT next_value FROM hms.document_sequence WHERE document_type = 'UHID'"))
                .isEqualTo(31);
        // The service path audited its registrations; the bulk path left one summary event.
        assertThat(count("SELECT count(*) FROM hms.audit_event WHERE organization_id = '" + org
                + "' AND action = 'PATIENT_CREATED'")).isEqualTo(10);
        assertThat(count("SELECT count(*) FROM hms.audit_event WHERE organization_id = '" + org
                + "' AND action = 'SEED_PATIENTS_LOADED'")).isEqualTo(1);

        // Synthetic identifiers only.
        assertThat(count("SELECT count(*) FROM hms.patient WHERE full_name NOT LIKE 'Test %'")).isZero();
        assertThat(count("SELECT count(*) FROM hms.patient WHERE phone !~ '^\\+910000[0-9]{6}$'")).isZero();
        assertThat(count("SELECT count(*) FROM hms.patient WHERE abha_hash IS NOT NULL")).isZero();

        // The [E3-2] search finds bulk-path patients (index 20 is one) by name, phone and UHID.
        String bulkName = SeedData.patientName(20);
        UUID adminId = inOrg(() -> jdbc.sql("SELECT id FROM hms.app_user WHERE email = 'seed.admin@seed.example'")
                .query(UUID.class).single());
        AuthenticatedPrincipal admin = new AuthenticatedPrincipal(adminId, org, "seed.admin@seed.example", "Admin",
                List.of("SYSTEM_ADMIN"), List.copyOf(Permissions.ALL));
        String bulkUhid = inOrg(() -> jdbc.sql("SELECT uhid FROM hms.patient WHERE phone = ?")
                .param(SeedData.phone(20)).query(String.class).single());
        Supplier<List<String>> byName = () -> patients.search(admin, bulkName, null, null, null, 0, 50).items()
                .stream().map(PatientSummary::uhid).toList();
        Supplier<List<String>> byPhone = () -> patients.search(admin, null, null, SeedData.phone(20), null, 0, 50)
                .items().stream().map(PatientSummary::uhid).toList();
        Supplier<List<String>> byUhid = () -> patients.search(admin, null, bulkUhid, null, null, 0, 50).items()
                .stream().map(PatientSummary::uhid).toList();
        assertThat(context.runAs(org, byName)).contains(bulkUhid);
        assertThat(context.runAs(org, byPhone)).containsExactly(bulkUhid);
        assertThat(context.runAs(org, byUhid)).containsExactly(bulkUhid);

        // Rerun: nothing is created, nothing changes.
        SeedSummary second = seed.run(options(30, 10));
        assertThat(second.isNoOp()).isTrue();
        assertThat(count("SELECT count(*) FROM hms.patient WHERE phone LIKE '+910000%'")).isEqualTo(30);
        assertThat(count("SELECT count(*) FROM hms.practitioner")).isEqualTo(5);

        // A larger target tops up the bulk path and continues the numbering.
        SeedSummary topUp = seed.run(options(45, 10));
        assertThat(topUp.patients()).isZero();
        assertThat(topUp.bulkPatients()).isEqualTo(15);
        assertThat(topUp.departments() + topUp.doctors() + topUp.users()).isZero();
        assertThat(count("SELECT count(*) FROM hms.patient WHERE phone LIKE '+910000%'")).isEqualTo(45);
        assertThat(inOrg(() -> jdbc.sql("SELECT max(uhid) FROM hms.patient").query(String.class).single()))
                .isEqualTo("HMS-000045");
    }
}
