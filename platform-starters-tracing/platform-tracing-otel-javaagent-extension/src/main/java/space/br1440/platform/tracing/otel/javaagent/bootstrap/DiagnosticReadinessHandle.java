package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import java.util.Objects;
import java.util.function.Supplier;

final class DiagnosticReadinessHandle {

    private AutoCloseable handle;
    private boolean closed;
    private Throwable closeFailure;

    synchronized void registerIfAbsent(Supplier<? extends AutoCloseable> registrationFactory) {
        Objects.requireNonNull(registrationFactory, "registrationFactory");
        if (closed || handle != null) {
            return;
        }

        handle = Objects.requireNonNull(
                registrationFactory.get(),
                "Diagnostic readiness registration must not be null"
        );
    }

    synchronized void close() {
        if (closed) {
            if (closeFailure != null) {
                rethrow(closeFailure);
            }
            return;
        }
        closed = true;

        AutoCloseable registration = handle;
        handle = null;
        if (registration == null) {
            return;
        }

        try {
            registration.close();
        } catch (RuntimeException | Error failure) {
            closeFailure = failure;
            throw failure;
        } catch (Exception failure) {
            IllegalStateException wrapped =
                    new IllegalStateException("Diagnostic readiness MBean cleanup failed", failure);
            closeFailure = wrapped;
            throw wrapped;
        }
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof Error error) {
            throw error;
        }

        throw (RuntimeException) failure;
    }
}
