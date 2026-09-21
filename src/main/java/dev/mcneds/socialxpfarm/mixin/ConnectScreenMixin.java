package dev.mcneds.socialxpfarm.mixin;

import dev.mcneds.socialxpfarm.ConnectionRecovery;
import dev.mcneds.socialxpfarm.ConnectionAttemptControl;
import io.netty.channel.ChannelFuture;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.TransferState;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ConnectScreen.class)
public abstract class ConnectScreenMixin implements ConnectionAttemptControl {
    @Shadow private volatile boolean aborted;
    @Shadow private volatile Connection connection;
    @Shadow private ChannelFuture channelFuture;

    @Override
    public void socialxpfarm$abort() {
        // Match vanilla cancellation's lock so a late DNS/connect result cannot establish a second session.
        synchronized (this) {
            aborted = true;
            if (channelFuture != null) {
                channelFuture.cancel(true);
                channelFuture = null;
            }
            if (connection != null) connection.disconnect(Component.literal("Connection attempt timed out"));
        }
    }
    @Inject(method = "startConnecting", at = @At("HEAD"))
    private static void socialxpfarm$rememberDestination(Screen parent, Minecraft client, ServerAddress address,
                                                        ServerData server, boolean quickPlay, TransferState transfer,
                                                        CallbackInfo ci) {
        ConnectionRecovery.INSTANCE.connecting(server);
    }
}
