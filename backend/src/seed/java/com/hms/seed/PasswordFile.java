package com.hms.seed;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.List;

/**
 * Writes the temporary administrator password to a file only its owner can read: mode 600 on POSIX file systems, an
 * ACL with a single owner entry (no inherited entries) elsewhere (Windows). If that cannot be guaranteed the file is
 * deleted and {@code false} is returned, so the caller shows the password once instead. Never logs the password.
 */
final class PasswordFile {

    private PasswordFile() {
    }

    /** @return true when the file was written and protected; false when nothing was left on disk */
    static boolean write(Path file, String password) {
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.deleteIfExists(file);
            if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
                Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            } else {
                Files.createFile(file);
                restrictToOwner(file);
            }
            Files.writeString(file, password + "\n", StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
            return true;
        } catch (IOException | RuntimeException e) {
            // Only the exception type is reported: the password must not leak through a message.
            try {
                Files.deleteIfExists(file);
            } catch (IOException ignored) {
                // best effort
            }
            return false;
        }
    }

    private static void restrictToOwner(Path file) throws IOException {
        AclFileAttributeView view = Files.getFileAttributeView(file, AclFileAttributeView.class);
        if (view == null) {
            throw new IOException("no ACL support");
        }
        UserPrincipal owner = Files.getOwner(file);
        AclEntry ownerOnly = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner)
                .setPermissions(EnumSet.of(AclEntryPermission.READ_DATA, AclEntryPermission.WRITE_DATA,
                        AclEntryPermission.APPEND_DATA, AclEntryPermission.READ_ATTRIBUTES,
                        AclEntryPermission.WRITE_ATTRIBUTES, AclEntryPermission.READ_NAMED_ATTRS,
                        AclEntryPermission.WRITE_NAMED_ATTRS, AclEntryPermission.READ_ACL,
                        AclEntryPermission.WRITE_ACL, AclEntryPermission.DELETE, AclEntryPermission.SYNCHRONIZE))
                .build();
        view.setAcl(List.of(ownerOnly)); // replaces every entry, inherited ones included
        if (view.getAcl().size() != 1) {
            throw new IOException("ACL not restricted");
        }
    }
}
