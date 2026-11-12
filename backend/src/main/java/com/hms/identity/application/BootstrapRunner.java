package com.hms.identity.application;

import com.hms.identity.domain.TemporaryPassword;
import com.hms.organization.api.OrganizationRules;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * First-run bootstrap ([E2-1]). When {@code HMS_BOOTSTRAP_ORG_NAME}, {@code HMS_BOOTSTRAP_ADMIN_EMAIL} (and
 * optionally {@code HMS_BOOTSTRAP_ORG_TIMEZONE}, default Asia/Kolkata) are set and no organization exists, creates
 * the organization, default roles and a System Administrator with a generated temporary password.
 *
 * <p>Binding ruling: the password goes to the file named by {@code HMS_BOOTSTRAP_ADMIN_PASSWORD_FILE} (owner-only
 * where the file system has POSIX permissions) and is never logged or audited. Variables set without the password
 * file fail startup, with a message that cannot contain the password. Running again is a no-op.
 */
@Component
class BootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapRunner.class);

    private final FirstOrganizationBootstrap bootstrap;
    private final String orgName;
    private final String adminEmail;
    private final String timezone;
    private final String passwordFile;

    BootstrapRunner(FirstOrganizationBootstrap bootstrap,
            @Value("${hms.bootstrap.org-name:}") String orgName,
            @Value("${hms.bootstrap.admin-email:}") String adminEmail,
            @Value("${hms.bootstrap.org-timezone:}") String timezone,
            @Value("${hms.bootstrap.admin-password-file:}") String passwordFile) {
        this.bootstrap = bootstrap;
        this.orgName = orgName.trim();
        this.adminEmail = adminEmail.trim();
        this.timezone = timezone.trim();
        this.passwordFile = passwordFile.trim();
    }

    @Override
    public void run(ApplicationArguments args) {
        if (orgName.isEmpty() && adminEmail.isEmpty() && timezone.isEmpty() && passwordFile.isEmpty()) {
            return; // not configured
        }
        if (orgName.isEmpty() || adminEmail.isEmpty()) {
            throw new IllegalStateException(
                    "Bootstrap needs both HMS_BOOTSTRAP_ORG_NAME and HMS_BOOTSTRAP_ADMIN_EMAIL.");
        }
        if (passwordFile.isEmpty()) {
            throw new IllegalStateException("HMS_BOOTSTRAP_ADMIN_PASSWORD_FILE must name the file that receives the "
                    + "temporary administrator password; the password is never logged.");
        }
        String zone = timezone.isEmpty() ? OrganizationRules.DEFAULT_TIMEZONE : timezone;
        if (!OrganizationRules.isIanaTimezone(zone)) {
            throw new IllegalStateException("HMS_BOOTSTRAP_ORG_TIMEZONE is not a valid IANA time zone id.");
        }
        if (bootstrap.organizationExists()) {
            log.info("Bootstrap skipped: an organization already exists.");
            return;
        }
        Path file = Path.of(passwordFile);
        String password = TemporaryPassword.generate();
        writeOwnerOnly(file, password);
        boolean created = false;
        try {
            Optional<UUID> org = bootstrap.bootstrap(orgName, zone, adminEmail, password);
            created = org.isPresent();
            if (created) {
                log.info("Bootstrap created organization {}; the temporary administrator password was written to "
                        + "the configured password file and must be changed at first login.", org.get());
            } else {
                log.info("Bootstrap skipped: an organization already exists.");
            }
        } finally {
            if (!created) {
                deleteQuietly(file); // do not leave a password that belongs to no account
            }
        }
    }

    private static void writeOwnerOnly(Path file, String password) {
        try {
            Files.deleteIfExists(file);
            if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
                Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            } else {
                Files.createFile(file);
            }
            Files.writeString(file, password + "\n", StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            // Only the exception type is reported: the message and cause carry no password, but stay minimal.
            throw new IllegalStateException("Cannot write the bootstrap password file (" + e.getClass().getSimpleName()
                    + ").");
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
