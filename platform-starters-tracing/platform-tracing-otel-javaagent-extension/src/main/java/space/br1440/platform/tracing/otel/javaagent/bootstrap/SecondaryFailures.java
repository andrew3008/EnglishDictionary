package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import java.util.Objects;

import space.br1440.platform.tracing.otel.javaagent.bootstrap.resources.BootstrapCleanupFailures;
import space.br1440.platform.tracing.otel.javaagent.resilience.PlatformThrowables;

final class SecondaryFailures {

    private final Throwable diagnosticRoot;
    private Throwable fatal;

    SecondaryFailures(Throwable diagnosticRoot, Throwable cause) {
        this.diagnosticRoot = Objects.requireNonNull(diagnosticRoot, "diagnosticRoot");
        this.fatal = PlatformThrowables.isFatal(cause) ? cause : null;
    }

    void noteFatal(Throwable candidate) {
        if (fatal == null && PlatformThrowables.isFatal(candidate)) {
            fatal = candidate;
        }
    }

    void suppress(Throwable secondary) {
        if (secondary == null) {
            return;
        }

        try {
            BootstrapCleanupFailures.suppressPreservingPrimary(diagnosticRoot, secondary);
        } catch (RuntimeException | Error inspectionFailure) {
            noteFatal(inspectionFailure);
        }
    }

    void record(Throwable candidate) {
        noteFatal(candidate);
        suppress(candidate);
    }

    Throwable firstFatal() {
        return fatal;
    }
}
