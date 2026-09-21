package dev.mcneds.socialxpfarm;

import java.util.function.LongSupplier;

/** Monotonic timing keeps menu FPS limits and system clock changes out of reconnect delays. */
final class RecoveryDeadline {
    private final LongSupplier clock;
    private long started;
    private long duration;
    private boolean armed;

    RecoveryDeadline() { this(System::nanoTime); }
    RecoveryDeadline(LongSupplier clock) { this.clock = clock; }

    void startTicks(int ticks) {
        started = clock.getAsLong();
        duration = (long) ticks * 50_000_000L;
        armed = true;
    }

    boolean expired() { return armed && clock.getAsLong() - started >= duration; }
    boolean active() { return armed && !expired(); }
    void clear() { armed = false; }
}
