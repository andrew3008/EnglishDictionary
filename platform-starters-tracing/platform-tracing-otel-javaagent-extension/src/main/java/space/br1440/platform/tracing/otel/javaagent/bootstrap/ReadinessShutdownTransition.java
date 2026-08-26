package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Одноразовый барьер, который снимает READY до начала deregistration.
 * До успешного READY commit закрытие остаётся безопасным no-op для rollback.
 */
final class ReadinessShutdownTransition implements AutoCloseable {

    private final AtomicReference<State> state = new AtomicReference<>(State.NEW);
    private final Runnable transition;

    ReadinessShutdownTransition(Runnable transition) {
        this.transition = Objects.requireNonNull(transition, "transition");
    }

    boolean arm() {
        return state.compareAndSet(State.NEW, State.ARMED);
    }

    @Override
    public void close() {
        State previous = state.getAndSet(State.CLOSED);
        if (previous == State.ARMED) {
            transition.run();
        }
    }

    private enum State {
        NEW,
        ARMED,
        CLOSED
    }
}
