package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import java.util.Objects;
import java.util.ServiceLoader;
import java.util.function.Supplier;

import space.br1440.platform.tracing.otel.javaagent.bootstrap.resources.BootstrapCleanupFailures;
import space.br1440.platform.tracing.otel.javaagent.logging.PlatformAgentLogger;
import space.br1440.platform.tracing.otel.javaagent.logging.event.BootstrapLogEvent;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogField;
import space.br1440.platform.tracing.otel.javaagent.resilience.PlatformThrowables;

import io.opentelemetry.javaagent.tooling.LoggingCustomizer;

public final class PlatformStartupLoggingCustomizer implements LoggingCustomizer {

    private final Supplier<BootstrapCoordinator> coordinatorSupplier;
    private final Supplier<Iterable<LoggingCustomizer>> loggingCustomizers;

    public PlatformStartupLoggingCustomizer() {
        this(ExtensionRuntime::bootstrap,
                () -> ServiceLoader.load(LoggingCustomizer.class, LoggingCustomizer.class.getClassLoader()));
    }

    PlatformStartupLoggingCustomizer(Supplier<BootstrapCoordinator> coordinatorSupplier,
                                     Supplier<Iterable<LoggingCustomizer>> loggingCustomizers) {
        this.coordinatorSupplier = Objects.requireNonNull(coordinatorSupplier, "coordinatorSupplier");
        this.loggingCustomizers = Objects.requireNonNull(loggingCustomizers, "loggingCustomizers");
    }

    @Override
    public String name() {
        return "platform";
    }

    @Override
    public void init() {
        LoggingCustomizer simple = null;
        for (LoggingCustomizer candidate : loggingCustomizers.get()) {
            if ("simple".equals(candidate.name())) {
                if (simple != null) {
                    throw new IllegalStateException("Multiple simple logging customizers found in the agent classloader");
                }

                simple = candidate;
            }
        }

        if (simple == null) {
            throw new IllegalStateException("Simple logging customizer not found in the agent classloader");
        }

        simple.init();
    }

    @Override
    public void onStartupSuccess() {
        // READY публикуется только после успешной активации платформы.
    }

    @Override
    public void onStartupFailure(Throwable cause) {
        BootstrapCoordinator coordinator = null;
        try {
            try {
                coordinator = coordinatorSupplier.get();
                coordinator.onStartupFailure(cause);
            } catch (RuntimeException | Error failure) {
                retainSecondary(cause, failure);
            }
        } finally {
            try {
                try {
                    emitStartupFailure(coordinator, cause);
                } catch (RuntimeException | Error loggingFailure) {
                    retainSecondary(cause, loggingFailure);
                }
            } finally {
                propagateFatalFailure(cause);
            }
        }
    }

    private static void emitStartupFailure(BootstrapCoordinator coordinator, Throwable cause) {
        PlatformAgentLogger.get(PlatformStartupLoggingCustomizer.class).log(BootstrapLogEvent.STARTUP_FAILED, fields -> {
            fields.cause(cause);

            if (coordinator != null) {
                BootstrapDiagnosticSnapshot snapshot = coordinator.diagnosticSnapshot();
                fields.putEnum(AgentLogField.LIFECYCLE, snapshot.state());
                BootstrapFailure failure = snapshot.failure();
                if (failure != null) {
                    fields.putString(AgentLogField.FAILURE_CODE, failure.effectiveCode());
                    fields.putString(AgentLogField.PRIMARY_FAILURE_CODE, failure.primaryCode());
                }
            }
        });
    }

    private static void retainSecondary(Throwable primary, Throwable secondary) {
        try {
            BootstrapCleanupFailures.suppressPreservingPrimary(primary, secondary);
        } catch (RuntimeException | Error suppressionFailure) {
            propagateFatalFailure(suppressionFailure);
        } finally {
            propagateFatalFailure(secondary);
        }
    }

    private static void propagateFatalFailure(Throwable failure) {
        try {
            PlatformThrowables.propagateFatalCauseChain(failure);
        } catch (RuntimeException | Error inspectionFailure) {
            PlatformThrowables.propagateIfFatal(inspectionFailure);
        }
    }
}
