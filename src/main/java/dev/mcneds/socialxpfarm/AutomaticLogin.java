package dev.mcneds.socialxpfarm;

import dev.mcneds.socialxpfarm.auth.*;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.network.chat.Component;
import java.io.IOException;

/** Minecraft/Auth Me adapter; tokens stay in this instance's private storage. */
final class AutomaticLogin {
    private static final RefreshTokenStore STORE = new RefreshTokenStore(
            FabricLoader.getInstance().getConfigDir().resolve("socialxpfarm-auth"));
    private static final MicrosoftAuthClient BACKEND = new MicrosoftAuthClient();

    static SessionRefresh create() { return new SessionRefresh(STORE, BACKEND); }

    static boolean available() {
        if (!FabricLoader.getInstance().isModLoaded("authme")) return false;
        try {
            Class.forName("me.axieum.mcmod.authme.api.util.SessionUtils").getMethod("setUser", User.class);
            return true;
        } catch (ReflectiveOperationException | LinkageError e) { return false; }
    }

    static boolean apply(User user) {
        try {
            Class.forName("me.axieum.mcmod.authme.api.util.SessionUtils").getMethod("setUser", User.class).invoke(null, user);
            return true;
        } catch (ReflectiveOperationException | LinkageError e) { return false; }
    }

    static int setup(Minecraft client) {
        RemoteLoginBridge.INSTANCE.cancelTest();
        if (!available()) {
            feedback(client, "Install Auth Me 9.3.0+26.2 to enable automatic session renewal.");
            return 0;
        }
        User expected = client.getUser();
        client.schedule(() -> client.setScreenAndShow(new AutomaticLoginScreen(null, create(), expected, true)));
        return 1;
    }

    static int status(Minecraft client) {
        try {
            feedback(client, STORE.load(client.getUser().getProfileId()).isPresent()
                    ? "Automatic login is paired for " + client.getUser().getName() + "."
                    : "No automatic login saved for this account. Run /sxp auth login once.");
        } catch (IOException e) { feedback(client, "Saved login is unreadable. Check permissions or run /sxp auth login again."); }
        return 1;
    }

    static int forget(Minecraft client) {
        RemoteLoginBridge.INSTANCE.cancelTest();
        ConnectionRecovery.INSTANCE.reset();
        try {
            STORE.forget();
            feedback(client, "Removed this instance's saved login. Automatic renewal needs pairing again.");
        } catch (IOException e) { feedback(client, "Could not remove the saved login. Check file permissions."); }
        return 1;
    }

    private static void feedback(Minecraft client, String message) {
        if (client.player != null) client.player.sendSystemMessage(Component.literal("[SocialXPFarm] " + message));
    }
}
