package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

class PreparationGateTest {

    @Test
    void leave_by_non_owner_is_rejected() throws Exception {
        // given
        PreparationGate gate = new PreparationGate(new BootstrapLifecycleState());
        FutureTask<Void> enter = new FutureTask<>(gate::enter, null);
        Thread.ofPlatform()
                .daemon()
                .name("preparation-gate-owner")
                .start(enter);
        enter.get(5, TimeUnit.SECONDS);

        // when / then
        assertThatThrownBy(gate::leave)
                .isInstanceOf(IllegalMonitorStateException.class)
                .hasMessage("Bootstrap preparation ownership can only be released by its owner");
    }

    @Test
    void interrupt_while_waiting_is_absorbed_and_restored_after_acquisition() throws Exception {
        // given
        BootstrapLifecycleState lifecycle = new BootstrapLifecycleState();
        PreparationGate gate = new PreparationGate(lifecycle);
        gate.enter();

        AtomicBoolean interruptedAfterAcquisition = new AtomicBoolean();
        FutureTask<BootstrapLifecycleState.CloseAcquisition> acquire = new FutureTask<>(() -> {
            BootstrapLifecycleState.CloseAcquisition acquisition = gate.acquireCloseWhenIdle();
            interruptedAfterAcquisition.set(Thread.currentThread().isInterrupted());
            return acquisition;
        });
        Thread waiter = Thread.ofPlatform()
                .daemon()
                .name("preparation-gate-close-waiter")
                .start(acquire);
        awaitWaiting(waiter);

        // when
        waiter.interrupt();
        awaitWaiting(waiter);
        gate.leave();

        // then
        assertThat(acquire.get(5, TimeUnit.SECONDS).owner()).isTrue();
        assertThat(interruptedAfterAcquisition).isTrue();
        assertThat(lifecycle.state()).isEqualTo(BootstrapState.CLOSING);
    }

    private static void awaitWaiting(Thread waiter) {
        await().pollDelay(Duration.ZERO)
                .pollInterval(Duration.ofMillis(5))
                .atMost(Duration.ofSeconds(5))
                .until(() -> !waiter.isInterrupted() && waiter.getState() == Thread.State.WAITING);
    }
}
