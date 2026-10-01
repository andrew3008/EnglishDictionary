package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import java.util.Objects;

import space.br1440.platform.tracing.otel.javaagent.bootstrap.resources.BootstrapResources;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.session.AutoconfigureSession;
import space.br1440.platform.tracing.otel.javaagent.logging.PlatformAgentLogger;
import space.br1440.platform.tracing.otel.javaagent.logging.event.BootstrapLogEvent;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogField;

final class BootstrapLifecycleState {

    private static final PlatformAgentLogger log = PlatformAgentLogger.get(BootstrapLifecycleState.class);

    private final Object monitor = new Object();

    private BootstrapState state = BootstrapState.NEW;
    private AutoconfigureSession session;
    private BootstrapFailure failure;
    private BootstrapResources resources;

    Object monitor() {
        return monitor;
    }

    void beginPrepare() {
        synchronized (monitor) {
            if (terminal()) {
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
                throw fail(
                        "SECOND_SDK_BUILD",
                        "prepare",
                        new IllegalStateException(
                                "Bootstrap coordinator already prepared/activated for this JVM; a second "
                                        + "AutoConfiguredOpenTelemetrySdk build is not supported"
                        )
                );
            }

            state = BootstrapState.PREPARING;
        }
    }

    void completePrepare(AutoconfigureSession prepared) {
        Objects.requireNonNull(prepared, "prepared");

        synchronized (monitor) {
            this.session = prepared;
            this.state = BootstrapState.PREPARED;
        }
    }

    AutoconfigureSession beginActivation() {
        synchronized (monitor) {
            if (state == BootstrapState.NEW) {
                throw fail(
                        "PLATFORM_ACP_NOT_EXECUTED",
                        "activate",
                        new IllegalStateException("""
                            AgentListener.afterAgent invoked without a prior ACP prepare() call \
                            (declarative configuration bypass or missing platform ACP registration)""")
                );
            }

            if (state == BootstrapState.PREPARING) {
                throw fail(
                        "REENTRANT_OR_OUT_OF_ORDER",
                        "activate",
                        new IllegalStateException("afterAgent invoked while bootstrap preparation is still in progress")
                );
            }

            if (state == BootstrapState.ACTIVATING) {
                throw fail(
                        "DUPLICATE_ACTIVATION",
                        "activate",
                        new IllegalStateException("afterAgent invoked more than once for this coordinator")
                );
            }

            if (state == BootstrapState.ACTIVE) {
                throw rejectWithoutStateChange(
                        "DUPLICATE_ACTIVATION",
                        "activate",
                        new IllegalStateException("afterAgent invoked more than once for this coordinator")
                );
            }

            if (terminal()) {
                throw stickyFailure("activate");
            }

            state = BootstrapState.ACTIVATING;
            return session;
        }
    }

    void requireOperable(String phase) {
        synchronized (monitor) {
            if (terminal()) {
                throw stickyFailure(phase);
            }
        }
    }

    AutoconfigureSession requireSession() {
        synchronized (monitor) {
            if (session == null) {
                throw stickyFailure("session");
            }

            return session;
        }
    }

    AutoconfigureSession currentSession() {
        synchronized (monitor) {
            return session;
        }
    }

    boolean active() {
        synchronized (monitor) {
            return state == BootstrapState.ACTIVE;
        }
    }

    BootstrapState state() {
        synchronized (monitor) {
            return state;
        }
    }

    void commitActive(AutoconfigureSession activeSession) {
        Objects.requireNonNull(activeSession, "activeSession");
        synchronized (monitor) {
            this.session = activeSession;
            this.state = BootstrapState.ACTIVE;
        }
    }

    void attachResources(BootstrapResources activationResources) {
        Objects.requireNonNull(activationResources, "activationResources");
        synchronized (monitor) {
            this.resources = activationResources;
        }
    }

    CloseTransition beginClose() {
        synchronized (monitor) {
            if (state == BootstrapState.CLOSED || state == BootstrapState.CLOSING) {
                return CloseTransition.alreadyClosing();
            }

            state = BootstrapState.CLOSING;
            BootstrapResources owned = resources;
            resources = null;
            return new CloseTransition(true, owned);
        }
    }

    void markClosed() {
        synchronized (monitor) {
            state = BootstrapState.CLOSED;
        }
    }

    void markFailed(String code, String phase, Throwable cause) {
        synchronized (monitor) {
            this.failure = new BootstrapFailure(code, phase, cause);
            this.state = BootstrapState.FAILED;
        }
    }

    BootstrapException fail(String code, String phase, Throwable cause) {
        return fail(code, phase, phase, null, cause);
    }

    BootstrapException fail(String code,
                            String phase,
                            String subphase,
                            String propertyName,
                            Throwable cause) {
        synchronized (monitor) {
            if (state == BootstrapState.FAILED) {
                logFailure(
                        BootstrapLogEvent.BOOTSTRAP_OPERATION_REJECTED,
                        code,
                        phase,
                        subphase,
                        propertyName,
                        cause
                );
                return new BootstrapException(failure);
            }

            this.failure = new BootstrapFailure(code, phase, cause);
            this.state = BootstrapState.FAILED;
            logFailure(BootstrapLogEvent.BOOTSTRAP_PHASE_FAILED, code, phase, subphase, propertyName, cause);

            return new BootstrapException(failure);
        }
    }

    @SuppressWarnings("SameParameterValue")
    BootstrapException rejectWithoutStateChange(String code, String phase, Throwable cause) {
        synchronized (monitor) {
            BootstrapFailure rejection = new BootstrapFailure(code, phase, cause);
            log.log(BootstrapLogEvent.BOOTSTRAP_OPERATION_REJECTED, fields -> fields
                    .putString(AgentLogField.FAILURE_CODE, code)
                    .putString(AgentLogField.PHASE, phase)
                    .putString(AgentLogField.SUBPHASE, phase)
                    .cause(cause));
            return new BootstrapException(rejection);
        }
    }

    BootstrapException stickyFailure(String phase) {
        synchronized (monitor) {
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

    private boolean terminal() {
        return state == BootstrapState.FAILED
                || state == BootstrapState.CLOSED
                || state == BootstrapState.CLOSING;
    }

    private static void logFailure(BootstrapLogEvent event,
                                   String code,
                                   String phase,
                                   String subphase,
                                   String propertyName,
                                   Throwable cause) {
        log.log(event, fields -> {
            fields.putString(AgentLogField.FAILURE_CODE, code)
                    .putString(AgentLogField.PHASE, phase)
                    .putString(AgentLogField.SUBPHASE, subphase);
            if (propertyName != null) {
                fields.putString(AgentLogField.PROPERTY_NAME, propertyName);
            }
            fields.cause(cause);
        });
    }

    record CloseTransition(boolean started, BootstrapResources resources) {

        static CloseTransition alreadyClosing() {
            return new CloseTransition(false, null);
        }
    }
}
