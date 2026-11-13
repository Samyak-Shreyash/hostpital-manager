package com.hms.seed;

import com.hms.audit.api.AuditEntry;
import com.hms.audit.api.AuditOutcome;
import com.hms.audit.api.AuditRecorder;
import com.hms.billing.application.ServiceCatalogService;
import com.hms.billing.application.ServiceView;
import com.hms.identity.api.AuthenticatedPrincipal;
import com.hms.identity.api.Permissions;
import com.hms.identity.application.FirstOrganizationBootstrap;
import com.hms.identity.application.UserManagementService;
import com.hms.identity.domain.TemporaryPassword;
import com.hms.organization.application.CatalogItemView;
import com.hms.organization.application.CatalogKind;
import com.hms.organization.application.CatalogService;
import com.hms.patient.application.ClinicalEntryService;
import com.hms.patient.application.PatientService;
import com.hms.patient.application.PatientView;
import com.hms.patient.application.RegisterPatientCommand;
import com.hms.practitioner.application.AvailabilityService;
import com.hms.practitioner.application.AvailabilityService.WindowInput;
import com.hms.practitioner.application.PractitionerCommands;
import com.hms.practitioner.application.PractitionerService;
import com.hms.practitioner.application.PractitionerView;
import com.hms.practitioner.application.StaffCommands;
import com.hms.practitioner.application.StaffService;
import com.hms.shared.domain.Money;
import com.hms.shared.id.UuidV7Generator;
import com.hms.shared.numbering.DocumentNumberAllocator;
import com.hms.shared.tenancy.OrganizationContext;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Synthetic dev/staging data ([E2-4]): one organization, departments, specialties, a service catalog with prices,
 * users with built-in roles, doctors, staff, weekly availability and leave, and patients with some allergies and
 * history. Idempotent: every row family is keyed on a natural key (codes, e-mails, registration numbers) or, for
 * patients, topped up to the requested total, so a rerun creates nothing that exists.
 *
 * <p>Everything except the bulk patient load goes through the modules' application services, so invariants,
 * encryption, UHID numbering and audit apply. The first {@value SeedOptions#DEFAULT_PATIENTS} patients also use
 * {@link PatientService#register}. Patients beyond that (the {@code --large} run, 100k for the [E3-2] search check)
 * use batched JDBC in the organization's RLS context: the per-patient duplicate scan and audit row of the service
 * would make 100k registrations take far too long. The bulk path still takes UHIDs from the gapless counter (one
 * block per batch, same locking), uses the E.164 phone form, honours every constraint (it runs as the runtime role),
 * and writes one summary audit event instead of 100k.
 *
 * <p>Tooling only: lives in the {@code seed} source set (not in the application jar), exists as a bean only when
 * {@code hms.seed.enabled=true}, and refuses to run outside dev/staging ({@link SeedGuard}). Logs counts, never values.
 */
@Component
@ConditionalOnProperty(name = "hms.seed.enabled", havingValue = "true")
public class SeedService {

    private static final Logger log = LoggerFactory.getLogger(SeedService.class);

    private static final int BATCH = 1_000;
    private static final String UHID = "UHID"; // PatientService's counter keys
    private static final String PERIOD_ALL = "ALL";

    private static final List<String[]> DEPARTMENTS = List.of(
            new String[] {"GENMED", "General Medicine"}, new String[] {"PEDS", "Paediatrics"},
            new String[] {"ORTHO", "Orthopaedics"}, new String[] {"CARDIO", "Cardiology"},
            new String[] {"ENT", "Ear, Nose and Throat"}, new String[] {"GYN", "Obstetrics and Gynaecology"});
    private static final List<String[]> SPECIALTIES = List.of(
            new String[] {"GENMED", "General Medicine"}, new String[] {"PEDS", "Paediatrics"},
            new String[] {"ORTHO", "Orthopaedics"}, new String[] {"CARDIO", "Cardiology"},
            new String[] {"ENT", "Ear, Nose and Throat"}, new String[] {"GYN", "Obstetrics and Gynaecology"});
    private static final List<String[]> SERVICES = List.of(
            new String[] {"CONSULT_GEN", "General consultation", "300.00"},
            new String[] {"CONSULT_SPEC", "Specialist consultation", "600.00"},
            new String[] {"FOLLOWUP", "Follow-up visit", "200.00"},
            new String[] {"ECG", "ECG", "350.00"}, new String[] {"XRAY_CHEST", "Chest X-ray", "500.00"},
            new String[] {"CBC", "Complete blood count", "250.00"}, new String[] {"LFT", "Liver function test", "700.00"},
            new String[] {"USG_ABD", "Ultrasound abdomen", "900.00"}, new String[] {"DRESSING", "Wound dressing", "150.00"},
            new String[] {"INJECTION", "Injection administration", "100.00"},
            new String[] {"PHYSIO", "Physiotherapy session", "400.00"},
            new String[] {"VACCINE_ADMIN", "Vaccine administration", "200.00"});
    private static final int DOCTORS = 5;
    /** Staff: role code (also the staff type) per record. */
    private static final List<String> STAFF_ROLES = List.of("RECEPTIONIST", "RECEPTIONIST", "NURSE", "NURSE",
            "ACCOUNTANT");

    private final Environment environment;
    private final OrganizationContext context;
    private final TransactionTemplate tx;
    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;
    private final FirstOrganizationBootstrap bootstrap;
    private final CatalogService catalog;
    private final ServiceCatalogService services;
    private final UserManagementService users;
    private final PractitionerService practitioners;
    private final StaffService staff;
    private final AvailabilityService availability;
    private final PatientService patients;
    private final ClinicalEntryService clinicalEntries;
    private final AuditRecorder audit;
    private final DocumentNumberAllocator numbers;
    private final UuidV7Generator ids;
    private final Clock clock;

    SeedService(Environment environment, OrganizationContext context, TransactionTemplate tx, JdbcClient jdbc,
            JdbcTemplate jdbcTemplate, FirstOrganizationBootstrap bootstrap, CatalogService catalog,
            ServiceCatalogService services, UserManagementService users, PractitionerService practitioners,
            StaffService staff, AvailabilityService availability, PatientService patients,
            ClinicalEntryService clinicalEntries, AuditRecorder audit, DocumentNumberAllocator numbers,
            UuidV7Generator ids, Clock clock) {
        this.environment = environment;
        this.context = context;
        this.tx = tx;
        this.jdbc = jdbc;
        this.jdbcTemplate = jdbcTemplate;
        this.bootstrap = bootstrap;
        this.catalog = catalog;
        this.services = services;
        this.users = users;
        this.practitioners = practitioners;
        this.staff = staff;
        this.availability = availability;
        this.patients = patients;
        this.clinicalEntries = clinicalEntries;
        this.audit = audit;
        this.numbers = numbers;
        this.ids = ids;
        this.clock = clock;
    }

    public SeedSummary run(SeedOptions options) {
        SeedGuard.check(environment);
        OrgResolution resolved = resolveOrganization(options);
        UUID org = resolved.id();
        AuthenticatedPrincipal admin = administrator(org);

        Map<String, UUID> departmentIds = new HashMap<>();
        Map<String, UUID> specialtyIds = new HashMap<>();
        int departments = seedCatalog(org, admin, CatalogKind.DEPARTMENT, DEPARTMENTS, departmentIds);
        int specialties = seedCatalog(org, admin, CatalogKind.SPECIALTY, SPECIALTIES, specialtyIds);
        int serviceCount = seedServices(org, admin);
        PeopleResult people = seedPeople(org, admin, departmentIds, specialtyIds);
        PatientResult patientResult = seedPatients(org, admin, options.patients(), options.servicePatients());

        SeedSummary summary = new SeedSummary(org, resolved.created(), departments, specialties, serviceCount,
                people.users(), people.doctors(), people.staff(), people.availability(), people.leaves(),
                patientResult.viaService(), patientResult.bulk(), patientResult.allergies(),
                patientResult.histories());
        log.info("Seed finished: {}", summary);
        return summary;
    }

    // --- organization ---------------------------------------------------------------------------------------------

    private record OrgResolution(UUID id, boolean created) {
    }

    /** The seed organization by name; else the only organization; else a new one through the [E2-1] bootstrap. */
    private OrgResolution resolveOrganization(SeedOptions options) {
        List<UUID> named = jdbc.sql("SELECT id FROM hms.organization WHERE name = ?").param(SeedData.SEED_ORG_NAME)
                .query(UUID.class).list();
        if (!named.isEmpty()) {
            return new OrgResolution(named.get(0), false);
        }
        List<UUID> all = jdbc.sql("SELECT id FROM hms.organization ORDER BY created_at").query(UUID.class).list();
        if (all.size() == 1) {
            log.info("Seeding into the existing organization.");
            return new OrgResolution(all.get(0), false);
        }
        if (!all.isEmpty()) {
            throw new IllegalStateException("Several organizations exist and none is named '" + SeedData.SEED_ORG_NAME
                    + "'; refusing to guess which one to seed.");
        }
        Path file = options.adminPasswordFile();
        String password = TemporaryPassword.generate();
        writeOwnerOnly(file, password);
        Optional<UUID> created = Optional.empty();
        try {
            created = bootstrap.bootstrap(SeedData.SEED_ORG_NAME, "Asia/Kolkata",
                    "seed.admin@" + SeedData.SEED_EMAIL_DOMAIN, password);
        } finally {
            if (created.isEmpty()) {
                deleteQuietly(file);
            }
        }
        if (created.isEmpty()) {
            throw new IllegalStateException("An organization appeared while seeding; run the seed again.");
        }
        log.info("Seed created the organization and its administrator; the temporary administrator password was "
                + "written to the password file and must be changed at first login.");
        return new OrgResolution(created.get(), true);
    }

    /** The acting user of the seed: the organization's first active System Administrator, with every permission. */
    private AuthenticatedPrincipal administrator(UUID org) {
        record Row(UUID id, String email, String fullName) {
        }
        Row row = inOrg(org, () -> jdbc.sql("""
                SELECT u.id, u.email, u.full_name FROM hms.app_user u
                JOIN hms.user_role ur ON ur.user_id = u.id
                JOIN hms.role r ON r.id = ur.role_id AND r.code = 'SYSTEM_ADMIN'
                WHERE u.status = 'ACTIVE' ORDER BY u.created_at LIMIT 1
                """).query((rs, n) -> new Row(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3)))
                .optional()).orElseThrow(() -> new IllegalStateException(
                "The organization has no active System Administrator to act as."));
        return new AuthenticatedPrincipal(row.id(), org, row.email(), row.fullName(), List.of("SYSTEM_ADMIN"),
                List.copyOf(Permissions.ALL));
    }

    // --- catalogs -------------------------------------------------------------------------------------------------

    private int seedCatalog(UUID org, AuthenticatedPrincipal admin, CatalogKind kind, List<String[]> wanted,
            Map<String, UUID> idsByCode) {
        Map<String, UUID> existing = new HashMap<>();
        asOrg(org, () -> catalog.list(admin, kind, null, 0, 100)).items()
                .forEach(i -> existing.put(i.code(), i.id()));
        int created = 0;
        for (String[] item : wanted) {
            UUID id = existing.get(item[0]);
            if (id == null) {
                CatalogItemView view = asOrg(org, () -> catalog.create(admin, kind, item[0], item[1]));
                id = view.id();
                created++;
            }
            idsByCode.put(item[0], id);
        }
        return created;
    }

    private int seedServices(UUID org, AuthenticatedPrincipal admin) {
        Map<String, ServiceView> existing = new HashMap<>();
        asOrg(org, () -> services.list(admin, null, 0, 100)).items().forEach(s -> existing.put(s.code(), s));
        int created = 0;
        for (String[] item : SERVICES) {
            ServiceView view = existing.get(item[0]);
            if (view == null) {
                view = asOrg(org, () -> services.create(admin, item[0], item[1]));
                created++;
            }
            if (view.currentPrice() == null) {
                UUID serviceId = view.id();
                asOrg(org, () -> services.addPrice(admin, serviceId, Money.of(item[2]), null));
            }
        }
        return created;
    }

    // --- users, doctors, staff, availability ----------------------------------------------------------------------

    private record PeopleResult(int users, int doctors, int staff, int availability, int leaves) {
    }

    private PeopleResult seedPeople(UUID org, AuthenticatedPrincipal admin, Map<String, UUID> departmentIds,
            Map<String, UUID> specialtyIds) {
        Map<String, UUID> userIds = new HashMap<>();
        Set<UUID> doctorUsers = new HashSet<>();
        Set<UUID> staffUsers = new HashSet<>();
        inOrg(org, () -> {
            jdbc.sql("SELECT email, id FROM hms.app_user WHERE email LIKE ?").param("%@" + SeedData.SEED_EMAIL_DOMAIN)
                    .query((rs, n) -> userIds.put(rs.getString(1), rs.getObject(2, UUID.class))).list();
            doctorUsers.addAll(jdbc.sql("SELECT user_id FROM hms.practitioner").query(UUID.class).list());
            staffUsers.addAll(jdbc.sql("SELECT user_id FROM hms.staff").query(UUID.class).list());
            return null;
        });
        int newUsers = 0;
        int newDoctors = 0;
        int newStaff = 0;
        int templates = 0;
        int leaves = 0;
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));

        List<String[]> specialties = SPECIALTIES;
        for (int d = 1; d <= DOCTORS; d++) {
            String email = "seed.doctor" + d + "@" + SeedData.SEED_EMAIL_DOMAIN;
            String name = "Test Doctor " + SeedData.NATO.get(d - 1);
            UUID userId = userIds.get(email);
            if (userId == null) {
                userId = asOrg(org, () -> users.create(admin, email, name, List.of("DOCTOR"))).user().id();
                userIds.put(email, userId);
                newUsers++;
            }
            if (doctorUsers.contains(userId)) {
                continue;
            }
            String[] specialty = specialties.get((d - 1) % specialties.size());
            UUID specialtyId = specialtyIds.get(specialty[0]);
            UUID departmentId = departmentIds.get(DEPARTMENTS.get((d - 1) % DEPARTMENTS.size())[0]);
            UUID doctorUser = userId;
            String registrationNo = String.format("SEED-DOC-%03d", d);
            PractitionerView doctor = asOrg(org, () -> practitioners.register(admin,
                    new PractitionerCommands.Register(doctorUser, name, registrationNo, "Seed Test Council",
                            List.of("MBBS"), 15, List.of(specialtyId), specialtyId, List.of(departmentId))));
            newDoctors++;
            List<WindowInput> windows = new java.util.ArrayList<>();
            for (int weekday = 1; weekday <= 5; weekday++) {
                windows.add(new WindowInput(weekday, "09:00", "13:00", null));
            }
            for (int weekday : new int[] {1, 3, 5}) {
                windows.add(new WindowInput(weekday, "16:00", "19:00", null));
            }
            asOrg(org, () -> availability.replace(admin, doctor.id(), doctor.version(), windows));
            templates++;
            // Some leave: a full day for odd doctors, a morning block for even ones.
            boolean fullDay = d % 2 == 1;
            LocalDate leaveDate = today.plusDays(7L + d);
            asOrg(org, () -> availability.createException(admin, doctor.id(), leaveDate, fullDay ? null : "09:00",
                    fullDay ? null : "11:00"));
            leaves++;
        }
        for (int s = 1; s <= STAFF_ROLES.size(); s++) {
            String role = STAFF_ROLES.get(s - 1);
            String email = "seed.staff" + s + "@" + SeedData.SEED_EMAIL_DOMAIN;
            String name = "Test Staff " + SeedData.NATO.get(DOCTORS + s - 1);
            UUID userId = userIds.get(email);
            if (userId == null) {
                userId = asOrg(org, () -> users.create(admin, email, name, List.of(role))).user().id();
                userIds.put(email, userId);
                newUsers++;
            }
            if (staffUsers.contains(userId)) {
                continue;
            }
            UUID staffUser = userId;
            UUID departmentId = departmentIds.get(DEPARTMENTS.get((s - 1) % DEPARTMENTS.size())[0]);
            asOrg(org, () -> staff.register(admin,
                    new StaffCommands.Register(staffUser, name, role, "Seed " + role.toLowerCase(java.util.Locale.ROOT),
                            departmentId)));
            newStaff++;
        }
        return new PeopleResult(newUsers, newDoctors, newStaff, templates, leaves);
    }

    // --- patients -------------------------------------------------------------------------------------------------

    private record PatientResult(int viaService, int bulk, int allergies, int histories) {
    }

    private PatientResult seedPatients(UUID org, AuthenticatedPrincipal admin, int target,
            int servicePatients) {
        int existing = inOrg(org, () -> jdbc.sql("SELECT count(*) FROM hms.patient WHERE phone LIKE ? AND full_name LIKE ?")
                .params(SeedData.PHONE_PREFIX + "%", SeedData.NAME_PREFIX + "%").query(Long.class).single()).intValue();
        int viaService = 0;
        int allergies = 0;
        int histories = 0;
        int serviceLimit = Math.min(target, servicePatients);
        for (int i = existing; i < serviceLimit; i++) {
            int index = i;
            PatientView patient = asOrg(org, () -> patients.register(admin, command(index), false));
            viaService++;
            if (i % 10 == 0) {
                String substance = SeedData.ALLERGENS.get((i / 10) % SeedData.ALLERGENS.size());
                String reaction = SeedData.REACTIONS.get((i / 10) % SeedData.REACTIONS.size());
                String severity = SeedData.SEVERITIES.get((i / 10) % SeedData.SEVERITIES.size());
                asOrg(org, () -> clinicalEntries.addAllergy(admin, patient.id(), substance, reaction, severity));
                allergies++;
            }
            if (i % 7 == 0) {
                String condition = SeedData.CONDITIONS.get((i / 7) % SeedData.CONDITIONS.size());
                asOrg(org, () -> clinicalEntries.addHistory(admin, patient.id(), condition, null));
                histories++;
            }
        }
        if (viaService > 0) {
            log.info("Seeded {} patients through the registration service.", viaService);
        }
        int bulk = 0;
        for (int start = Math.max(existing, servicePatients); start < target; start += BATCH) {
            int from = start;
            int to = Math.min(target, start + BATCH);
            inOrg(org, () -> insertBatch(org, admin.userId(), from, to));
            bulk += to - from;
            if (bulk % (BATCH * 10) == 0) {
                log.info("Bulk-loaded {} patients so far.", bulk);
            }
        }
        if (bulk > 0) {
            int loaded = bulk;
            inOrg(org, () -> {
                audit.record(new AuditEntry(org, admin.userId(), "SEED_PATIENTS_LOADED", "PATIENT", null,
                        AuditOutcome.SUCCESS, List.of(), loaded));
                return null;
            });
            log.info("Seeded {} patients through the bulk path.", bulk);
        }
        return new PatientResult(viaService, bulk, allergies, histories);
    }

    private static RegisterPatientCommand command(int i) {
        RegisterPatientCommand.Address address = new RegisterPatientCommand.Address("Test Street " + (i % 500 + 1), null,
                "Testville", "Testland", "000000");
        RegisterPatientCommand.EmergencyContact contact = i % 4 == 0 ? null
                : new RegisterPatientCommand.EmergencyContact(
                        SeedData.NAME_PREFIX + "Contact " + SeedData.NATO.get(i % SeedData.NATO.size()), "Friend",
                        SeedData.contactPhone(i));
        return new RegisterPatientCommand(SeedData.patientName(i), SeedData.sex(i), SeedData.dateOfBirth(i), null,
                false, SeedData.phone(i), address, contact, null, false, null);
    }

    /** One transaction (in the organization's RLS context): a UHID block, the patients and their addresses. */
    private Integer insertBatch(UUID org, UUID actorId, int from, int to) {
        int n = to - from;
        long firstUhid = numbers.nextBlock(org, UHID, PERIOD_ALL, n);
        Timestamp now = Timestamp.from(clock.instant());
        UUID[] patientIds = new UUID[n];
        for (int k = 0; k < n; k++) {
            patientIds[k] = ids.next();
        }
        jdbcTemplate.batchUpdate("""
                INSERT INTO hms.patient (id, organization_id, uhid, full_name, sex, date_of_birth, dob_estimated,
                    phone, status, created_by, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, false, ?, 'ACTIVE', ?, ?, ?)
                """, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int k) throws SQLException {
                int i = from + k;
                ps.setObject(1, patientIds[k]);
                ps.setObject(2, org);
                ps.setString(3, SeedData.uhid(firstUhid + k));
                ps.setString(4, SeedData.patientName(i));
                ps.setString(5, SeedData.sex(i));
                ps.setObject(6, SeedData.dateOfBirth(i));
                ps.setString(7, SeedData.phone(i));
                ps.setObject(8, actorId);
                ps.setTimestamp(9, now);
                ps.setTimestamp(10, now);
            }

            @Override
            public int getBatchSize() {
                return n;
            }
        });
        UUID[] addressIds = new UUID[n];
        for (int k = 0; k < n; k++) {
            addressIds[k] = ids.next();
        }
        jdbcTemplate.batchUpdate("""
                INSERT INTO hms.patient_address (id, organization_id, patient_id, line1, city, state, pincode,
                    created_at, updated_at)
                VALUES (?, ?, ?, ?, 'Testville', 'Testland', '000000', ?, ?)
                """, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int k) throws SQLException {
                ps.setObject(1, addressIds[k]);
                ps.setObject(2, org);
                ps.setObject(3, patientIds[k]);
                ps.setString(4, "Test Street " + ((from + k) % 500 + 1));
                ps.setTimestamp(5, now);
                ps.setTimestamp(6, now);
            }

            @Override
            public int getBatchSize() {
                return n;
            }
        });
        return n;
    }

    // --- plumbing -------------------------------------------------------------------------------------------------

    /** Runs {@code work} (which opens its own transactions, e.g. an application service call) in the organization. */
    private <T> T asOrg(UUID org, Supplier<T> work) {
        return context.runAs(org, work);
    }

    /** Runs {@code work} in one transaction bound to the organization (RLS), for the seed's own queries. */
    private <T> T inOrg(UUID org, Supplier<T> work) {
        Supplier<T> transactional = () -> tx.execute(status -> work.get());
        return context.runAs(org, transactional);
    }

    private static void writeOwnerOnly(Path file, String password) {
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.deleteIfExists(file);
            if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
                Files.createFile(file,
                        java.nio.file.attribute.PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            } else {
                Files.createFile(file);
            }
            Files.writeString(file, password + "\n", StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            // Only the exception type is reported: the password must not leak through a message.
            throw new IllegalStateException("Cannot write the admin password file (" + e.getClass().getSimpleName() + ").");
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // best effort
        }
    }
}
