package dev.mcneds.socialxpfarm;

import net.minecraft.client.User;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static dev.mcneds.socialxpfarm.AuthenticationRecovery.Result.*;
import static org.junit.jupiter.api.Assertions.*;

class AuthenticationRecoveryTest {
    private static final UUID ALT_A = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ALT_B = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private final Object disconnect = new Object();
    private final Object methodChooser = new Object();
    private final Object microsoftLogin = new Object();
    private final AuthenticationRecovery<Object> recovery = new AuthenticationRecovery<>();
    private final AtomicInteger opened = new AtomicInteger();
    private final User stale = user(ALT_A, "AltA", "synthetic-expired-token");

    private static User user(UUID id, String name, String token) {
        return new User(name, id, token, Optional.empty(), Optional.empty());
    }

    private void begin() { recovery.begin(disconnect, stale, opened::incrementAndGet); }

    @Test
    void staleSessionHandsOffOnceAndWaitsAtMethodChooser() {
        assertEquals(RecoveryPolicy.Action.AUTHENTICATE,
                RecoveryPolicy.classify(Component.translatable("disconnect.loginFailedInfo.invalidSession")));
        begin();
        // Arbitrarily many ticks at the screenshot's screen must not reopen login or release the gate.
        for (int tick = 0; tick < 10_000; tick++) {
            recovery.begin(disconnect, stale, opened::incrementAndGet);
            assertEquals(WAITING, recovery.poll(methodChooser, stale));
        }
        assertEquals(1, opened.get());
    }

    @Test
    void refreshedSessionWaitsForAuthMeToReturnToOriginalDisconnectScreen() {
        begin();
        User renewed = user(ALT_A, "AltA", "synthetic-renewed-token");
        assertEquals(WAITING, recovery.poll(microsoftLogin, renewed));
        assertEquals(WAITING, recovery.poll(methodChooser, renewed));
        assertEquals(WAITING, recovery.poll(new Object(), renewed));
        assertEquals(READY, recovery.poll(disconnect, renewed));
        assertEquals(IDLE, recovery.poll(disconnect, renewed)); // Schedule the reconnect only once.
        assertEquals(1, opened.get());
    }

    @Test
    void browserCancellationOrFailureAndBackToChooserDoNotResume() {
        begin();
        assertEquals(WAITING, recovery.poll(microsoftLogin, stale));
        assertEquals(WAITING, recovery.poll(methodChooser, stale));
        assertEquals(WAITING, recovery.poll(disconnect, stale));
        assertEquals(WAITING, recovery.poll(disconnect, stale));
        assertEquals(1, opened.get());
        // The user may retry manually using Auth Me's Re-Login button.
        assertEquals(WAITING, recovery.poll(microsoftLogin, stale));
        assertEquals(READY, recovery.poll(disconnect, user(ALT_A, "AltA", "synthetic-renewed-token")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"synthetic-expired-token", "", "   ", "invalidtoken"})
    void replacementUserWithUnchangedBlankOrOfflineTokenCannotResume(String token) {
        begin();
        assertEquals(WAITING, recovery.poll(disconnect, user(ALT_A, "AltA", token)));
    }

    @Test
    void wrongMicrosoftAccountStaysPausedUntilOriginalAltSignsIn() {
        begin();
        User otherAlt = user(ALT_B, "AltB", "synthetic-other-account-token");
        assertEquals(WAITING, recovery.poll(microsoftLogin, otherAlt));
        assertEquals(WRONG_ACCOUNT, recovery.poll(disconnect, otherAlt));
        assertEquals("AltA", recovery.expectedName());
        assertEquals(WAITING, recovery.poll(disconnect, otherAlt)); // Do not warn every tick.
        assertEquals(1, opened.get()); // Do not repeatedly launch browser windows.
        assertEquals(READY, recovery.poll(disconnect, user(ALT_A, "AltA", "synthetic-correct-account-token")));
    }

    @Test
    void identityUsesMinecraftUuidRatherThanDisplayName() {
        begin();
        assertEquals(WRONG_ACCOUNT, recovery.poll(disconnect, user(ALT_B, "AltA", "synthetic-other-token")));
        assertEquals(READY, recovery.poll(disconnect, user(ALT_A, "RenamedAlt", "synthetic-new-token")));
    }

    @Test
    void separateInstancesRequireTheirOwnAltEvenWhenBrowserAccountIsShared() {
        begin();
        var second = new AuthenticationRecovery<Object>();
        Object secondDisconnect = new Object();
        User secondStale = user(ALT_B, "AltB", "synthetic-alt-b-expired");
        second.begin(secondDisconnect, secondStale, () -> {});
        User renewedA = user(ALT_A, "AltA", "synthetic-alt-a-renewed");
        assertEquals(READY, recovery.poll(disconnect, renewedA));
        assertEquals(WRONG_ACCOUNT, second.poll(secondDisconnect, renewedA));
        assertEquals(READY, second.poll(secondDisconnect, user(ALT_B, "AltB", "synthetic-alt-b-renewed")));
    }

    @Test
    void missingOrUnavailableAuthMeLeavesGatePaused() {
        // The optional adapter handles an unavailable mod by returning without opening a screen.
        recovery.begin(disconnect, stale, () -> {});
        assertEquals(WAITING, recovery.poll(disconnect, stale));
        assertEquals(READY, recovery.poll(disconnect, user(ALT_A, "AltA", "synthetic-manually-renewed")));
    }

    @Test
    void resetDiscardsPendingAuthenticationAndLateCompletion() {
        begin();
        // ConnectionRecovery clears this state on disabling, manual departure, or another server.
        recovery.clear();
        assertEquals(IDLE, recovery.poll(disconnect, user(ALT_A, "AltA", "synthetic-late-token")));
        assertEquals(1, opened.get());
    }

    @Test
    void newFailureAfterResetCanStartAuthenticationForAnotherAccount() {
        begin();
        recovery.clear();
        Object nextDisconnect = new Object();
        recovery.begin(nextDisconnect, user(ALT_B, "AltB", "synthetic-rejected-b"), opened::incrementAndGet);
        assertEquals("AltB", recovery.expectedName());
        assertEquals(2, opened.get());
        assertEquals(READY, recovery.poll(nextDisconnect, user(ALT_B, "AltB", "synthetic-renewed-b")));
    }

    @Test
    void laterExpiredSessionCanStartAnotherAuthenticationCycle() {
        begin();
        User renewed = user(ALT_A, "AltA", "synthetic-new-token");
        assertEquals(READY, recovery.poll(disconnect, renewed));
        Object laterDisconnect = new Object();
        recovery.begin(laterDisconnect, renewed, opened::incrementAndGet);
        assertEquals(WAITING, recovery.poll(laterDisconnect, renewed));
        assertEquals(READY, recovery.poll(laterDisconnect, user(ALT_A, "AltA", "synthetic-newer-token")));
        assertEquals(2, opened.get());
    }
}
