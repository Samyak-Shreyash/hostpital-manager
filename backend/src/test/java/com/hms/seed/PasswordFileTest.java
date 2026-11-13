package com.hms.seed;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** [E2-4] The administrator password file is readable by its owner only. */
class PasswordFileTest {

    @TempDir
    Path dir;

    @Test
    void writesAnOwnerOnlyFile() throws Exception {
        Path file = dir.resolve("sub").resolve("pw.txt");

        assertThat(PasswordFile.write(file, "Synthetic-Pass-1")).isTrue();

        assertThat(Files.readString(file)).isEqualTo("Synthetic-Pass-1\n");
        if (Files.getFileStore(file).supportsFileAttributeView("posix")) {
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file))).isEqualTo("rw-------");
        } else {
            AclFileAttributeView acl = Files.getFileAttributeView(file, AclFileAttributeView.class);
            assertThat(acl.getAcl()).hasSize(1);
            assertThat(acl.getAcl().get(0).principal()).isEqualTo(Files.getOwner(file));
        }
    }

    @Test
    void leavesNothingBehindWhenTheFileCannotBeWritten() throws Exception {
        Path blocker = dir.resolve("blocker");
        Files.writeString(blocker, "x");

        assertThat(PasswordFile.write(blocker.resolve("pw.txt"), "Synthetic-Pass-1")).isFalse();
        assertThat(Files.exists(blocker.resolve("pw.txt"))).isFalse();
    }
}
