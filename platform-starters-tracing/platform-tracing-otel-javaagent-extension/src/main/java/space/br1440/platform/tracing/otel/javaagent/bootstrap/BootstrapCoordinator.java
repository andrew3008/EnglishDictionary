package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionConfig;
import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;
import space.br1440.platform.tracing.otel.javaagent.control.jmx.lifecycle.PrivateControlPlane;
import space.br1440.platform.tracing.otel.javaagent.diagnostics.PlatformLogControl;
import space.br1440.platform.tracing.otel.javaagent.jmx.readiness.ExtensionReadinessWireSnapshot;
import space.br1440.platform.tracing.otel.javaagent.propagation.PlatformPropagationGate;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionCapability;
import space.br1440.platform.tracing.otel.javaagent.resilience.PlatformThrowables;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.PlatformScrubberFactory;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.SanitizerBootstrapException;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.ScrubbingRuntime;

import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigurationException;
import lombok.extern.slf4j.Slf4j;

@Slf4j
final class BootstrapCoordinator {

    private final Object lock = new Object();
    private final ShutdownHookRegistry hookRegistry;
    private final PlatformScrubberFactory scrubberFactory;

    private final AtomicReference<BootstrapSnapshot> snapshot = new AtomicReference<>(
            BootstrapSnapshot.initializing()
    );

    private BootstrapState state = BootstrapState.NEW;
    private AutoconfigureSession session;
    private BootstrapFailure failure;

    @SuppressWarnings({"unused", "FieldCanBeLocal"})
    private AutoCloseable diagnosticHandle;

    static BootstrapCoordinator create() {
        return create(new PlatformScrubberFactory());
    }

    static BootstrapCoordinator create(PlatformScrubberFactory scrubberFactory) {
        return new BootstrapCoordinator(JvmShutdownHookRegistry.INSTANCE, scrubberFactory);
    }

    BootstrapCoordinator(ShutdownHookRegistry hookRegistry) {
        this(hookRegistry, new PlatformScrubberFactory());
    }

    BootstrapCoordinator(ShutdownHookRegistry hookRegistry, PlatformScrubberFactory scrubberFactory) {
        this.hookRegistry = Objects.requireNonNull(hookRegistry, "hookRegistry");
        this.scrubberFactory = Objects.requireNonNull(scrubberFactory, "scrubberFactory");
    }

    AutoconfigureSession prepare(ConfigProperties config) {
        Objects.requireNonNull(config, "config");
        ConfigurationFingerprint fingerprint = ConfigurationFingerprint.of(config);

        synchronized (lock) {
            if (state == BootstrapState.FAILED
                    || state == BootstrapState.CLOSED
                    || state == BootstrapState.CLOSING) {
                throw stickyFailure("prepare");
            }

            if (state == BootstrapState.PREPARING) {
                throw fail(
                        "REENTRANT_OR_OUT_OF_ORDER",
                        "prepare",
                        new IllegalStateException("Bootstrap preparation is already in progress")
                );
            }

            if (state != BootstrapState.NEW) {
                boolean sameConfiguration = session != null && fingerprint.equals(session.fingerprint());
                String code = sameConfiguration ? "SECOND_SDK_BUILD" : "CONFIGURATION_SPLIT_BRAIN";
                throw fail(
                        code,
                        "prepare",
                        new IllegalStateException(
                                "Bootstrap coordinator already prepared/activated for this JVM; a second "
                                        + "AutoConfiguredOpenTelemetrySdk build is not supported"
                        )
                );
            }

            state = BootstrapState.PREPARING;
        }

        try {
            ExtensionConfig extensionConfig = new ExtensionConfig(config);
            if (!extensionConfig.scrubbing().enabled()) {
                String message = "%s=false is forbidden by the secure Agent profile"
                        .formatted(ExtensionPropertyNames.SCRUBBING_ENABLED);

                throw new ConfigurationException(message);
            }

            TracingSettingsSource policy = TracingSettingsSource.fromStartup(extensionConfig);
            ScrubbingRuntime scrubbingRuntime = scrubberFactory.buildRuntime(extensionConfig, config);
            AutoconfigureSession prepared = new AutoconfigureSession(
                    UUID.randomUUID(), fingerprint, extensionConfig, policy, scrubbingRuntime
            );
            prepared.readiness().markInstalled(PlatformExtensionCapability.CONFIGURATION_LOADED);

            synchronized (lock) {
                this.session = prepared;
                this.state = BootstrapState.PREPARED;
            }
            return prepared;
        } catch (SanitizerBootstrapException e) {
            throw fail(e.code(), "prepare", e);
        } catch (RuntimeException | Error e) {
            PlatformThrowables.propagateIfFatal(e);
            throw fail("CONFIGURATION_INITIALIZATION_FAILED", "prepare", e);
        }
    }

    AutoconfigureSession session() {
        synchronized (lock) {
            if (session == null) {
                throw stickyFailure("session");
            }
            return session;
        }
    }

    <T> T execute(String phaseFailureCode, Supplier<T> action) {
        synchronized (lock) {
            if (state == BootstrapState.FAILED
                    || state == BootstrapState.CLOSED
                    || state == BootstrapState.CLOSING) {
                throw stickyFailure(phaseFailureCode);
            }
        }

        try {
            return action.get();
        } catch (SanitizerBootstrapException e) {
            BootstrapException classified = fail(e.code(), phaseFailureCode, e);
            markSessionFailed(e.code(), e);
            throw classified;
        } catch (RuntimeException | Error e) {
            PlatformThrowables.propagateIfFatal(e);
            throw fail(phaseFailureCode, phaseFailureCode, e);
        }
    }

    private void markSessionFailed(String failureCode, Throwable cause) {
        AutoconfigureSession failedSession;
        synchronized (lock) {
            failedSession = session;
        }

        if (failedSession != null) {
            publishFailedSnapshot(failedSession, failureCode, cause);
        }
    }

    void activate() {
        AutoconfigureSession activeSession;
        synchronized (lock) {
            if (state == BootstrapState.NEW) {
                throw fail("PLATFORM_ACP_NOT_EXECUTED", "activate",
                        new IllegalStateException(
                                "AgentListener.afterAgent invoked without a prior ACP prepare() call "
                                + "(declarative configuration bypass or missing platform ACP registration)"
                        )
                );
            }

            if (state == BootstrapState.PREPARING) {
                throw fail("REENTRANT_OR_OUT_OF_ORDER", "activate",
                        new IllegalStateException("afterAgent invoked while bootstrap preparation is still in progress"));
            }

            if (state == BootstrapState.ACTIVATING || state == BootstrapState.ACTIVE) {
                throw fail("DUPLICATE_ACTIVATION", "activate",
                        new IllegalStateException("afterAgent invoked more than once for this coordinator")
                );
            }

            if (state == BootstrapState.FAILED
                    || state == BootstrapState.CLOSED
                    || state == BootstrapState.CLOSING) {
                throw stickyFailure("activate");
            }

            state = BootstrapState.ACTIVATING;
            activeSession = session;
        }

        snapshot.set(
                BootstrapSnapshot.initializing(
                        activeSession,
                        ExtensionReadinessWireSnapshot.initializing(activeSession.readiness())
                )
        );

        try {
            diagnosticHandle = activeSession.jmxRegistrar().registerDiagnosticReadiness(() -> snapshot.get().wire());
        } catch (RuntimeException | Error e) {
            PlatformThrowables.propagateIfFatal(e);
            BootstrapException classified = fail("DIAGNOSTIC_MBEAN_REGISTRATION_FAILED", "activate", e);
            publishFailedSnapshot(activeSession, "DIAGNOSTIC_MBEAN_REGISTRATION_FAILED", e);
            throw classified;
        }

        BootstrapResources resources = new BootstrapResources(hookRegistry);
        try {
            resources.push(PrivateControlPlane.activate(activeSession.policy()), "private-control-plane");
            resources.push(PlatformPropagationGate.shared().bindAcceptedPolicy(activeSession.policy()),"propagation-gate");
            resources.push(PlatformLogControl.shared().bindAcceptedPolicy(activeSession.policy()),"log-control");
            resources.push(activeSession.jmxRegistrar().registerRuntimeControls(activeSession.policy()),"runtime-jmx-controls");
            resources.installShutdownHook("platform-tracing-bootstrap-shutdown");

            activeSession.scrubbingRuntime().requireInstallationComplete();
            activeSession.readiness().markInstalled(PlatformExtensionCapability.SANITIZER_INSTALLED);
            activeSession.readiness().requireReady();

            ExtensionReadinessWireSnapshot ready = ExtensionReadinessWireSnapshot.ready(activeSession.readiness());
            transitionTo(
                    BootstrapState.ACTIVE,
                    activeSession,
                    BootstrapSnapshot.of(activeSession, BootstrapState.ACTIVE, null, null, ready)
            );
        } catch (RuntimeException | Error e) {
            rollback(resources, e);
            PlatformThrowables.propagateIfFatal(e);

            BootstrapException classified = fail("ACTIVATION_FAILED", "activate", e);
            publishFailedSnapshot(activeSession, "ACTIVATION_FAILED", e);
            throw classified;
        }
    }

    BootstrapSnapshot currentSnapshot() {
        return snapshot.get();
    }

    private void publishFailedSnapshot(AutoconfigureSession activeSession, String failureCode, Throwable cause) {
        activeSession.readiness().fail(failureCode, cause);
        ExtensionReadinessWireSnapshot failed = ExtensionReadinessWireSnapshot.failed(activeSession.readiness(), failureCode, cause.toString());
        snapshot.set(BootstrapSnapshot.of(activeSession, BootstrapState.FAILED, failureCode, cause.toString(), failed));
    }

    @SuppressWarnings("SameParameterValue")
    private void transitionTo(BootstrapState newState, AutoconfigureSession newSession, BootstrapSnapshot newSnapshot) {
        synchronized (lock) {
            this.session = newSession;
            this.state = newState;
        }

        snapshot.set(newSnapshot);
    }

    private static void rollback(BootstrapResources resources, Throwable primary) {
        try {
            resources.close();
        } catch (RuntimeException cleanupFailure) {
            primary.addSuppressed(cleanupFailure);
        }
    }

    private BootstrapException fail(String code, String phase, Throwable cause) {
        synchronized (lock) {
            if (state != BootstrapState.FAILED) {
                this.failure = new BootstrapFailure(code, phase, cause);
                this.state = BootstrapState.FAILED;
            }

            log.warn("Platform bootstrap classified failure [{}] during phase '{}': {}",
                    failure.code(), phase, cause == null ? "n/a" : cause.toString());

            return new BootstrapException(failure);
        }
    }

    private BootstrapException stickyFailure(String phase) {
        synchronized (lock) {
            if (failure == null) {
                BootstrapFailure cause = new BootstrapFailure(
                        "BOOTSTRAP_TERMINAL",
                        phase,
                        new IllegalStateException("Bootstrap coordinator is in terminal state " + state)
                );
                return new BootstrapException(cause);
            }

            BootstrapFailure cause = new BootstrapFailure(failure.code(), phase, failure.cause());
            return new BootstrapException(cause);
        }
    }
}
