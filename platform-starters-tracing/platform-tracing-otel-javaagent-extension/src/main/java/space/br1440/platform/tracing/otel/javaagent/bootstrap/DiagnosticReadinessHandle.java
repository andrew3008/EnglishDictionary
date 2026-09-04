package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import java.util.concurrent.atomic.AtomicReference;

final class DiagnosticReadinessHandle {

    private final AtomicReference<AutoCloseable> handle = new AtomicReference<>();

    AutoCloseable replace(AutoCloseable registration) {
        return handle.getAndSet(registration);
    }

    void close() {
        AutoCloseable registration = handle.getAndSet(null);
        if (registration == null) {
            return;
        }

        try {
            registration.close();
        } catch (Exception failure) {
            throw new IllegalStateException("Diagnostic readiness MBean cleanup failed", failure);
        }
    }
}
