package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import java.util.Objects;
import java.util.ServiceLoader;
import java.util.function.Supplier;

import space.br1440.platform.tracing.otel.javaagent.bootstrap.failure.BootstrapFailure;
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
        return "space.br1440.platform";
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
        // Первый наблюдённый fatal выбирается в порядке: исходный cause, доставка в coordinator, summary-лог.
        Throwable fatal = PlatformThrowables.firstFatalInCauseChain(cause);
        BootstrapCoordinator coordinator = null;
        try {
            coordinator = coordinatorSupplier.get();
            coordinator.onStartupFailure(cause);
        } catch (RuntimeException | Error deliveryFailure) {
            fatal = retainSecondary(cause, deliveryFailure, fatal);
        }

        try {
            emitStartupFailure(coordinator, cause);
        } catch (RuntimeException | Error loggingFailure) {
            fatal = retainSecondary(cause, loggingFailure, fatal);
        }

        PlatformThrowables.propagateIfFatal(fatal);
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

    /**
     * Присоединяет secondary к исходному cause best-effort и возвращает выбранный fatal, ничего не бросая.
     * Cause-цепочка secondary обходится всегда, чтобы восстановить interrupt flag даже при уже выбранном fatal.
     */
    private static Throwable retainSecondary(Throwable primary, Throwable secondary, Throwable selectedFatal) {
        Throwable fatal = firstFatal(selectedFatal, PlatformThrowables.firstFatalInCauseChain(secondary));
        try {
            BootstrapCleanupFailures.suppressPreservingPrimary(primary, secondary);
        } catch (RuntimeException | Error suppressionFailure) {
            fatal = firstFatal(fatal, PlatformThrowables.firstFatalInCauseChain(suppressionFailure));
        }

        return fatal;
    }

    private static Throwable firstFatal(Throwable selected, Throwable candidate) {
        return selected != null ? selected : candidate;
    }
}
