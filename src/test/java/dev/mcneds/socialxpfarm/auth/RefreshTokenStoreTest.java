package dev.mcneds.socialxpfarm.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class RefreshTokenStoreTest {
    @TempDir Path temp;
    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();

    @Test void oldCredentialFilesAcquireTheOriginalOAuthClientId() throws Exception {
        Path directory = Files.createDirectory(temp.resolve("auth"));
        Files.writeString(directory.resolve("account.json"), "{\"uuid\":\"" + a + "\",\"name\":\"AltA\",\"refreshToken\":\"synthetic-old\"}");
        var credential = new RefreshTokenStore(directory).load(a).orElseThrow();
        assertEquals(MicrosoftAuthClient.CLIENT_ID, credential.clientId());
        assertEquals("synthetic-old", credential.refreshToken());
    }

    @Test void explicitlyBoundOAuthClientSurvivesPersistence() throws Exception {
        var store = new RefreshTokenStore(temp.resolve("auth"));
        String clientId = UUID.randomUUID().toString();
        store.save(new RefreshTokenStore.Credential(a, "AltA", "synthetic-secret", clientId));
        assertEquals(clientId, store.load(a).orElseThrow().clientId());
    }

    @Test void persistsRotationWithOwnerOnlyPermissionsAndNoTemporaryCopies() throws Exception {
        Path directory = temp.resolve("auth");
        var store = new RefreshTokenStore(directory);
        store.save(new RefreshTokenStore.Credential(a, "AltA", "synthetic-original"));
        store.save(new RefreshTokenStore.Credential(a, "AltA", "synthetic-rotated"));
        assertEquals("synthetic-rotated", new RefreshTokenStore(directory).load(a).orElseThrow().refreshToken());
        try (var files = Files.list(directory)) { assertEquals(1, files.count()); }
        if (Files.getFileStore(directory).supportsFileAttributeView("posix")) {
            assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(directory));
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(directory.resolve("account.json")));
        }
    }

    @Test void differentInstancesAndUuidsDoNotShareCredentials() throws Exception {
        var first = new RefreshTokenStore(temp.resolve("first"));
        var second = new RefreshTokenStore(temp.resolve("second"));
        first.save(new RefreshTokenStore.Credential(a, "AltA", "synthetic-a"));
        second.save(new RefreshTokenStore.Credential(b, "AltB", "synthetic-b"));
        assertTrue(first.load(b).isEmpty());
        assertTrue(second.load(a).isEmpty());
        assertEquals("synthetic-a", first.load(a).orElseThrow().refreshToken());
        assertEquals("synthetic-b", second.load(b).orElseThrow().refreshToken());
    }

    @Test void forgetAndMissingStoreNeedPairingAgain() throws Exception {
        var store = new RefreshTokenStore(temp.resolve("auth"));
        assertTrue(store.load(a).isEmpty());
        store.save(new RefreshTokenStore.Credential(a, "AltA", "synthetic-secret"));
        store.forget();
        assertTrue(store.load(a).isEmpty());
    }

    @Test void corruptCredentialIsNotPrintedInErrorsOrToString() throws Exception {
        Path directory = Files.createDirectory(temp.resolve("auth"));
        Files.writeString(directory.resolve("account.json"), "{\"refreshToken\":\"synthetic-secret\"");
        IOException error = assertThrows(IOException.class, () -> new RefreshTokenStore(directory).load(a));
        assertFalse(error.toString().contains("synthetic-secret"));
        assertNull(error.getCause());
        assertFalse(new RefreshTokenStore.Credential(a, "AltA", "synthetic-secret").toString().contains("synthetic-secret"));
    }

    @Test void symbolicLinkCannotRedirectCredentialWrites() throws Exception {
        assumeTrue(Files.getFileStore(temp).supportsFileAttributeView("posix"));
        Path outside = Files.createDirectory(temp.resolve("outside"));
        Path linked = Files.createSymbolicLink(temp.resolve("auth"), outside);
        var store = new RefreshTokenStore(linked);
        assertThrows(IOException.class, () -> store.save(new RefreshTokenStore.Credential(a, "AltA", "synthetic-secret")));
        assertFalse(Files.exists(outside.resolve("account.json")));
    }
}
