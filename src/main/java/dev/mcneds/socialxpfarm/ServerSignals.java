package dev.mcneds.socialxpfarm;

import net.minecraft.client.Minecraft;

public final class ServerSignals {
    public static final ServerSignals INSTANCE = new ServerSignals();
    private final RecoveryDeadline queue = new RecoveryDeadline();
    private final RecoveryDeadline throttle = new RecoveryDeadline();

    private ServerSignals() {}

    public void accept(String text) {
        Minecraft client = Minecraft.getInstance();
        if (client.getCurrentServer() == null || !RecoveryPolicy.isHypixel(client.getCurrentServer().ip)) return;
        if (RecoveryPolicy.isQueueMessage(text)) queue.startTicks(6000); // Refreshed by position messages; five-minute stale limit.
        if (RecoveryPolicy.isThrottleMessage(text)) throttle.startTicks(400);
    }

    boolean queued() { return queue.active(); }
    boolean throttled() { return throttle.active(); }
    void clearQueue() { queue.clear(); }
    void reset() { queue.clear(); throttle.clear(); }
}
