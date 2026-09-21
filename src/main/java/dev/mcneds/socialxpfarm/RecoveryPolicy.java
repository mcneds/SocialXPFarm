package dev.mcneds.socialxpfarm;

import java.util.Locale;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

final class RecoveryPolicy {
    enum Action { RETRY, COOLDOWN, PROTOCOL, AUTHENTICATE, MANUAL }
    private RecoveryPolicy() {}

    static boolean isHypixel(String address) {
        if (address == null) return false;
        String host = address.trim().toLowerCase(Locale.ROOT).split(":", 2)[0];
        if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        return host.equals("hypixel.net") || host.endsWith(".hypixel.net");
    }

    static int reconnectDelay(int initial, int maximum, int previousAttempts) {
        return (int) Math.min(maximum, (long) initial << Math.clamp(previousAttempts, 0, 30));
    }

    static boolean isSessionKey(String key) {
        return switch (key) {
            case "disconnect.loginFailedInfo.invalidSession",
                 "multiplayer.disconnect.unverified_username", "multiplayer.disconnect.invalid_public_key_signature",
                 "multiplayer.disconnect.invalid_public_key_signature.new",
                 "multiplayer.disconnect.expired_public_key", "multiplayer.disconnect.missing_public_key" -> true;
            default -> false;
        };
    }

    static boolean isSessionMessage(String message) {
        String text = message.toLowerCase(Locale.ROOT).replaceAll("§.", "");
        return text.contains("invalid session") || text.contains("session has expired")
                || text.contains("session expired") || text.contains("expired session")
                || text.contains("failed to verify username") || text.contains("not authenticated with minecraft.net");
    }

    static Action classify(Component reason) {
        Action result = classifyMessage(reason.getString());
        if (reason.getContents() instanceof TranslatableContents translated) {
            result = strongest(result, classifyKey(translated.getKey()));
            for (Object argument : translated.getArgs()) {
                if (argument instanceof Component child) result = strongest(result, classify(child));
                else if (argument instanceof String text) result = strongest(result, classifyMessage(text));
            }
        }
        for (Component child : reason.getSiblings()) result = strongest(result, classify(child));
        return result;
    }

    private static Action strongest(Action first, Action second) {
        return first.ordinal() >= second.ordinal() ? first : second;
    }

    static Action classifyKey(String key) {
        if (key.startsWith("multiplayer.disconnect.banned") || isSessionKey(key)) {
            return isSessionKey(key) ? Action.AUTHENTICATE : Action.MANUAL;
        }
        return switch (key) {
            case "disconnect.loginFailedInfo.userBanned", "disconnect.loginFailedInfo.insufficientPrivileges",
                 "multiplayer.disconnect.ip_banned", "multiplayer.disconnect.not_whitelisted",
                 "multiplayer.disconnect.duplicate_login", "multiplayer.disconnect.name_taken",
                 "multiplayer.disconnect.incompatible", "multiplayer.disconnect.outdated_client",
                 "multiplayer.disconnect.outdated_server", "multiplayer.disconnect.code_of_conduct",
                 "multiplayer.requiredTexturePrompt.disconnect", "menu.custom_screen_info.disconnect" -> Action.MANUAL;
            case "disconnect.exceeded_packet_rate", "disconnect.spam", "multiplayer.disconnect.server_full",
                 "multiplayer.disconnect.too_many_pending_chats" -> Action.COOLDOWN;
            case "disconnect.packetError", "multiplayer.disconnect.bad_chat_index",
                 "multiplayer.disconnect.chat_validation_failed", "multiplayer.disconnect.configuration_error",
                 "multiplayer.disconnect.illegal_characters", "multiplayer.disconnect.invalid_entity_attacked",
                 "multiplayer.disconnect.invalid_packet", "multiplayer.disconnect.invalid_player_data",
                 "multiplayer.disconnect.invalid_player_movement", "multiplayer.disconnect.invalid_vehicle_movement",
                 "multiplayer.disconnect.missing_tags", "multiplayer.disconnect.out_of_order_chat",
                 "multiplayer.disconnect.transfers_disabled", "multiplayer.disconnect.unexpected_query_response",
                 "multiplayer.disconnect.unsigned_chat" -> Action.PROTOCOL;
            default -> Action.RETRY;
        };
    }

    static Action classifyMessage(String message) {
        String text = normalize(message);
        if (text.contains("banned") || text.contains("suspicious activity") || text.contains("blocked username")
                || text.contains("not whitelisted") || text.contains("not white-listed")
                || text.contains("multiplayer is disabled") || text.contains("logged in from another location")
                || text.contains("logged in from another device") || text.contains("outdated client")
                || text.contains("outdated server") || text.contains("incompatible client")
                || text.contains("requires a custom resource pack") || text.contains("code of conduct")) return Action.MANUAL;
        if (isSessionMessage(text)) return Action.AUTHENTICATE;
        if (text.contains("decoderexception") || text.contains("encoderexception")
                || text.contains("network protocol error") || text.contains("badly compressed packet")
                || text.contains("invalid packet") || text.contains("chat message validation failure")) return Action.PROTOCOL;
        if (text.contains("already connected") || text.contains("already logged in")
                || text.contains("logging in too fast") || text.contains("connecting too fast")
                || text.contains("too many connections") || text.contains("connection throttle")
                || text.contains("packet rate") || text.contains("kicked for spamming")
                || text.contains("server is full") || text.contains("under maintenance")) return Action.COOLDOWN;
        return Action.RETRY;
    }

    static String normalize(String text) {
        return text.replaceAll("§.", "").strip().toLowerCase(Locale.ROOT);
    }

    static boolean isQueueMessage(String text) {
        String value = normalize(text);
        return value.startsWith("you are in queue") || value.startsWith("you are in the queue")
                || value.startsWith("you are currently in queue") || value.startsWith("queue position:")
                || value.matches("you are #[\\d,]+ in (?:the )?queue[.!]?.*");
    }

    static boolean isThrottleMessage(String text) {
        String value = normalize(text);
        return value.startsWith("you are sending commands too fast")
                || value.startsWith("you are sending too many commands")
                || value.startsWith("please wait a bit before doing this");
    }
}
