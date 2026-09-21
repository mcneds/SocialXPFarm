package dev.mcneds.socialxpfarm.mixin;

import dev.mcneds.socialxpfarm.ServerSignals;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class QueueTitleMixin {
    // TAIL runs after Minecraft's main-thread dispatch guard.
    @Inject(method = "setTitleText", at = @At("TAIL"))
    private void socialxpfarm$title(ClientboundSetTitleTextPacket packet, CallbackInfo ci) {
        ServerSignals.INSTANCE.accept(packet.text().getString());
    }

    @Inject(method = "setSubtitleText", at = @At("TAIL"))
    private void socialxpfarm$subtitle(ClientboundSetSubtitleTextPacket packet, CallbackInfo ci) {
        ServerSignals.INSTANCE.accept(packet.text().getString());
    }
}
