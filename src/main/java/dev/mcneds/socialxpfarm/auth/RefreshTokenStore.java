package dev.mcneds.socialxpfarm.auth;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;

/** One private credential file inside this Minecraft instance; never uses launcher credentials. */
public final class RefreshTokenStore {
    public record Credential(UUID uuid, String name, String refreshToken) {
        public Credential {
            Objects.requireNonNull(uuid);
            if (name == null || name.isBlank() || refreshToken == null || refreshToken.isBlank())
                throw new IllegalArgumentException("Incomplete saved account");
        }
        @Override public String toString() { return "Credential[uuid=" + uuid + ", token=REDACTED]"; }
    }

    private static final Gson GSON = new Gson();
    private final Path directory;
    private final Path file;

    public RefreshTokenStore(Path directory) {
        this.directory = directory;
        file = directory.resolve("account.json");
    }

    public synchronized Optional<Credential> load(UUID expected) throws IOException {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        rejectLink(directory);
        rejectLink(file);
        protect(directory, true);
        protect(file, false);
        try {
            Credential value = GSON.fromJson(Files.readString(file), Credential.class);
            if (value == null) throw new IllegalArgumentException();
            return value.uuid().equals(expected) ? Optional.of(value) : Optional.empty();
        } catch (RuntimeException e) {
            throw new IOException("Saved account could not be read; pair the instance again.");
        }
    }

    public synchronized void save(Credential value) throws IOException {
        rejectLink(directory);
        if (!Files.exists(directory)) {
            if (Files.getFileStore(directory.getParent()).supportsFileAttributeView("posix"))
                Files.createDirectory(directory, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            else Files.createDirectory(directory);
        }
        protect(directory, true);
        rejectLink(file);
        Path temporary = Files.createTempFile(directory, "account-", ".tmp");
        try {
            protect(temporary, false);
            Files.writeString(temporary, GSON.toJson(value));
            // Never leave a partly written refresh token after a crash during replacement.
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public synchronized void forget() throws IOException {
        rejectLink(directory);
        rejectLink(file);
        Files.deleteIfExists(file);
    }

    private static void rejectLink(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) throw new IOException("Account storage must not be a symbolic link.");
    }

    private static void protect(Path path, boolean directory) throws IOException {
        PosixFileAttributeView posix = Files.getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (posix != null) {
            posix.setPermissions(PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"));
            return;
        }
        AclFileAttributeView acl = Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (acl == null) throw new IOException("This filesystem cannot restrict saved account access.");
        acl.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(acl.getOwner())
                .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build()));
    }
}
