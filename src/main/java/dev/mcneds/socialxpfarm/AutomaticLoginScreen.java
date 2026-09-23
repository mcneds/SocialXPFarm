package dev.mcneds.socialxpfarm;

import dev.mcneds.socialxpfarm.auth.SessionRefresh;
import net.minecraft.client.User;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

/** Only needed for initial consent or when Microsoft refuses silent renewal. */
final class AutomaticLoginScreen extends Screen {
    private final Screen parent;
    private final SessionRefresh refresh;
    private final User expected;
    private final boolean startImmediately;
    private boolean started;
    private boolean completed;
    private MultiLineTextWidget status;
    private Button signIn;

    AutomaticLoginScreen(Screen parent, SessionRefresh refresh, User expected, boolean startImmediately) {
        super(Component.literal("Automatic login for " + expected.getName()));
        this.parent = parent;
        this.refresh = refresh;
        this.expected = expected;
        this.startImmediately = startImmediately;
    }

    @Override protected void init() {
        addRenderableWidget(new MultiLineTextWidget(Component.literal(title.getString()
                + "\nSign in once with this alt's Microsoft account.\nFuture expired sessions renew automatically."), font)
                .setMaxWidth(Math.min(380, width - 30)).setCentered(true)).setPosition(Math.max(15, width / 2 - 190), height / 2 - 85);
        status = addRenderableWidget(new MultiLineTextWidget(Component.empty(), font)
                .setMaxWidth(Math.min(380, width - 30)).setCentered(true));
        status.setPosition(Math.max(15, width / 2 - 190), height / 2 - 25);
        signIn = addRenderableWidget(Button.builder(Component.literal("Sign in with Microsoft"), button -> begin())
                .bounds(width / 2 - 110, height / 2 + 30, 220, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Cancel"), button -> onClose())
                .bounds(width / 2 - 110, height / 2 + 55, 220, 20).build());
        if (!started && startImmediately) begin();
    }

    private void begin() {
        started = true;
        refresh.pair(expected, uri -> Util.getPlatform().openUri(uri));
    }

    @Override public void tick() {
        refresh.tick();
        status.setMessage(Component.literal(refresh.status() == SessionRefresh.Status.IDLE
                ? "Sign-in cancelled." : refresh.message()));
        signIn.active = refresh.status() != SessionRefresh.Status.RUNNING;
        if (refresh.status() == SessionRefresh.Status.READY) {
            completed = true;
            if (parent == null && minecraft.player != null)
                minecraft.player.sendSystemMessage(Component.literal("[SocialXPFarm] Automatic login saved for " + expected.getName() + "."));
            minecraft.setScreenAndShow(parent);
        }
    }

    @Override public void onClose() {
        refresh.cancel();
        minecraft.setScreenAndShow(parent);
    }

    @Override public void removed() {
        if (!completed) refresh.cancel();
    }

    @Override public boolean isPauseScreen() { return false; }
}
