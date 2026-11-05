package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import java.util.Objects;

final class PreparationGate {

    private final BootstrapLifecycleState lifecycle;
    private final Object monitor = new Object();

    private Thread owner;

    PreparationGate(BootstrapLifecycleState lifecycle) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
    }

    void enter() {
        synchronized (monitor) {
            lifecycle.beginPrepare();
            owner = Thread.currentThread();
        }
    }

    void leave() {
        synchronized (monitor) {
            if (owner != Thread.currentThread()) {
                throw new IllegalMonitorStateException(
                        "Bootstrap preparation ownership can only be released by its owner"
                );
            }

            owner = null;
            monitor.notifyAll();
        }
    }

    CloseOperation.CloseAcquisition acquireCloseWhenIdle() {
        boolean interrupted = false;
        try {
            synchronized (monitor) {
                if (owner == Thread.currentThread()) {
                    throw new IllegalStateException(
                            "Bootstrap close cannot run reentrantly during preparation"
                    );
                }

                while (owner != null) {
                    try {
                        monitor.wait();
                    } catch (InterruptedException ignored) {
                        interrupted = true;
                    }
                }

                return lifecycle.acquireClose();
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
