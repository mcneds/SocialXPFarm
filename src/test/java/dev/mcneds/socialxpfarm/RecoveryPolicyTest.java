package dev.mcneds.socialxpfarm;

import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class RecoveryPolicyTest {
    @Test
    void nativeConnectionResetFromDisconnectScreenKeepsRetrying() {
        Component reason = Component.translatable("disconnect.genericReason",
                "Internal Exception: io.netty.channel.unix.Errors$NativeIoException: "
                        + "recvAddress(..) failed with error(-104): Connection reset by peer");
        assertEquals(RecoveryPolicy.Action.RETRY, RecoveryPolicy.classify(reason));
        assertFalse(ConnectionRecovery.isSessionFailure(reason));
    }

    @ParameterizedTest
    @ValueSource(strings = {"multiplayer.disconnect.banned.reason", "multiplayer.disconnect.banned_ip.expiration",
            "disconnect.loginFailedInfo.insufficientPrivileges", "multiplayer.disconnect.duplicate_login",
            "multiplayer.disconnect.outdated_client", "multiplayer.requiredTexturePrompt.disconnect"})
    void permanentErrorsRequireIntervention(String key) {
        assertEquals(RecoveryPolicy.Action.MANUAL, RecoveryPolicy.classify(Component.translatable(key)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"You are temporarily banned from this server!", "Your account is blocked for suspicious activity",
            "You logged in from another location", "Incompatible client! Please use another version"})
    void recognizesCustomPermanentReasons(String message) {
        assertEquals(RecoveryPolicy.Action.MANUAL, RecoveryPolicy.classify(Component.literal(message)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"You are already connected to this server!", "You are logging in too fast!",
            "Server is full!", "This server is under maintenance", "Kicked for exceeding packet rate limit"})
    void retryingTooFastWouldExtendDowntime(String message) {
        assertEquals(RecoveryPolicy.Action.COOLDOWN, RecoveryPolicy.classify(Component.literal(message)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Network Protocol Error", "Internal Exception: io.netty.handler.codec.DecoderException",
            "Badly compressed packet - size too large", "Chat message validation failure"})
    void protocolErrorsHaveABoundedRetryPolicy(String message) {
        assertEquals(RecoveryPolicy.Action.PROTOCOL, RecoveryPolicy.classify(Component.literal(message)));
    }

    @Test
    void manualRestrictionTakesPriorityOverSessionText() {
        assertEquals(RecoveryPolicy.Action.MANUAL, RecoveryPolicy.classify(
                Component.translatable("multiplayer.disconnect.banned.reason", Component.literal("Invalid session"))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"§eYou are #123 in the queue!", "You are #1,234 in queue!", "Queue position: 50",
            "You are currently in queue for Hypixel", "You are in the queue"})
    void recognizesQueuePositions(String message) {
        assertTrue(RecoveryPolicy.isQueueMessage(message));
    }

    @ParameterizedTest
    @ValueSource(strings = {"[MVP+] Player: You are #123 in the queue!", "There was an error joining the queue!",
            "You left the queue!", "Sending you to mini123..."})
    void unrelatedMessagesDoNotFreezeRecovery(String message) {
        assertFalse(RecoveryPolicy.isQueueMessage(message));
    }

    @Test
    void commandThrottleMustBeAServerNotice() {
        assertTrue(RecoveryPolicy.isThrottleMessage("§cYou are sending commands too fast! Please slow down."));
        assertFalse(RecoveryPolicy.isThrottleMessage("Player: You are sending commands too fast!"));
    }
    @ParameterizedTest
    @ValueSource(strings = {"hypixel.net", "mc.hypixel.net:25565", " MC.HYPIXEL.NET ", "hypixel.net."})
    void acceptsHypixelAddresses(String address) {
        assertTrue(RecoveryPolicy.isHypixel(address));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "localhost", "nothypixel.net", "hypixel.net.example.com", "[::1]:25565"})
    void neverReconnectsToOtherHosts(String address) {
        assertFalse(RecoveryPolicy.isHypixel(address));
    }

    @Test
    void repeatedFailuresBackOffAndRemainCapped() {
        assertEquals(200, RecoveryPolicy.reconnectDelay(200, 1200, 0));
        assertEquals(400, RecoveryPolicy.reconnectDelay(200, 1200, 1));
        assertEquals(800, RecoveryPolicy.reconnectDelay(200, 1200, 2));
        assertEquals(1200, RecoveryPolicy.reconnectDelay(200, 1200, 3));
        assertEquals(1200, RecoveryPolicy.reconnectDelay(200, 1200, Integer.MAX_VALUE));
        assertEquals(Integer.MAX_VALUE, RecoveryPolicy.reconnectDelay(Integer.MAX_VALUE, Integer.MAX_VALUE, 30));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Invalid session (Try restarting your game and the launcher)",
            "§cYour session has expired!", "Failed to verify username!", "Not authenticated with Minecraft.net"})
    void staleSessionsRequireLogin(String message) {
        assertTrue(ConnectionRecovery.isSessionFailure(Component.literal(message)));
    }

    @Test
    void recognizesNestedTranslationKeysWithoutEnglishText() {
        Component reason = Component.translatable("disconnect.loginFailedInfo",
                Component.translatable("disconnect.loginFailedInfo.invalidSession"));
        assertTrue(ConnectionRecovery.isSessionFailure(reason));
        assertTrue(ConnectionRecovery.isSessionFailure(Component.empty().append(
                Component.translatable("multiplayer.disconnect.expired_public_key"))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Timed out", "Connection reset", "You were kicked for being AFK",
            "Authentication servers are down. Please try again later."})
    void ordinaryDisconnectsDoNotOpenAuthentication(String message) {
        assertFalse(ConnectionRecovery.isSessionFailure(Component.literal(message)));
    }

    @Test
    void authenticationServiceOutageDoesNotRequireNewCredentials() {
        assertFalse(ConnectionRecovery.isSessionFailure(Component.translatable("disconnect.loginFailedInfo",
                Component.translatable("disconnect.loginFailedInfo.serversUnavailable"))));
    }
}
