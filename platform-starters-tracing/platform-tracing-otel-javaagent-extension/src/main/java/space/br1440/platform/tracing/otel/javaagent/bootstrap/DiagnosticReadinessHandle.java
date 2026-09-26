package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import java.util.Objects;
import java.util.function.Supplier;

final class DiagnosticReadinessHandle {

    private AutoCloseable handle;

    synchronized void registerIfAbsent(Supplier<? extends AutoCloseable> registrationFactory) {
        Objects.requireNonNull(registrationFactory, "registrationFactory");
        if (handle != null) {
            return;
        }

        handle = Objects.requireNonNull(
                registrationFactory.get(),
                "Diagnostic readiness registration must not be null"
        );
    }

    synchronized AutoCloseable replace(AutoCloseable registration) {
        AutoCloseable previous = handle;
        handle = Objects.requireNonNull(registration, "registration");
        return previous;
    }

    synchronized void close() {
        AutoCloseable registration = handle;
        handle = null;
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
