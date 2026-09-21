package dev.mcneds.socialxpfarm;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class RecoveryDeadlineTest {
    @Test
    void elapsedTimeExpiresEvenWhenNoClientTicksWereDelivered() {
        AtomicLong time = new AtomicLong();
        RecoveryDeadline deadline = new RecoveryDeadline(time::get);
        assertFalse(deadline.expired());
        deadline.startTicks(200);
        time.set(9_999_999_999L);
        assertTrue(deadline.active());
        time.incrementAndGet();
        assertTrue(deadline.expired());
        assertFalse(deadline.active());
        deadline.clear();
        assertFalse(deadline.expired());
    }

    @Test
    void refreshedQueueSignalExtendsItsLifetime() {
        AtomicLong time = new AtomicLong();
        RecoveryDeadline deadline = new RecoveryDeadline(time::get);
        deadline.startTicks(6000);
        time.set(290_000_000_000L);
        deadline.startTicks(6000);
        time.set(310_000_000_000L);
        assertTrue(deadline.active());
        time.set(590_000_000_000L);
        assertTrue(deadline.expired());
    }

    @Test
    void largeConfigValuesAndNanoTimeWrapDoNotOverflow() {
        AtomicLong time = new AtomicLong(Long.MAX_VALUE - 10);
        RecoveryDeadline deadline = new RecoveryDeadline(time::get);
        deadline.startTicks(Integer.MAX_VALUE);
        time.addAndGet(50_000_000L);
        assertTrue(deadline.active());
        deadline.startTicks(20);
        time.addAndGet(1_000_000_000L);
        assertTrue(deadline.expired());
    }
}
