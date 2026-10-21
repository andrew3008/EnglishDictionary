package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapFailureCodes.BOOTSTRAP_TERMINAL;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapFailureCodes.DUPLICATE_ACTIVATION;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapFailureCodes.FAILED_ROLLBACK_INCOMPLETE;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapFailureCodes.REENTRANT_OR_OUT_OF_ORDER;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapFailureCodes.SECOND_SDK_BUILD;

import java.util.Objects;

import space.br1440.platform.tracing.otel.javaagent.bootstrap.resources.BootstrapCleanupFailures;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.resources.BootstrapResources;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.session.AutoconfigureSession;
import space.br1440.platform.tracing.otel.javaagent.logging.PlatformAgentLogger;
import space.br1440.platform.tracing.otel.javaagent.logging.event.BootstrapLogEvent;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogField;

final class BootstrapLifecycleState {

    private static final PlatformAgentLogger log = PlatformAgentLogger.get(BootstrapLifecycleState.class);

    private static final String PREPARE_PHASE = "prepare";
    private static final String ACTIVATE_PHASE = "activate";

    private final Object monitor = new Object();

    private BootstrapState state = BootstrapState.NEW;
    private AutoconfigureSession session;
    private BootstrapFailure failure;
    private BootstrapResources resources;
    private boolean closeStarted;

    Object monitor() {
        return monitor;
    }

    void beginPrepare() {
        synchronized (monitor) {
            if (terminal()) {
                throw stickyFailure(PREPARE_PHASE);
            }

            state = switch (state) {
                case NEW -> BootstrapState.PREPARING;

                case PREPARING -> throw reject(
                        REENTRANT_OR_OUT_OF_ORDER,
                        PREPARE_PHASE,
                        "Bootstrap preparation is already in progress"
                );

                default -> throw reject(
                        SECOND_SDK_BUILD,
                        PREPARE_PHASE,
                        """
                        Bootstrap coordinator already prepared/activated for this JVM; \
                        a second AutoConfiguredOpenTelemetrySdk build is not supported"""
                );
            };
        }
    }

    void completePrepare(AutoconfigureSession prepared) {
        Objects.requireNonNull(prepared, "prepared");

        synchronized (monitor) {
            if (state != BootstrapState.PREPARING) {
                if (failure != null) {
                    throw stickyFailure(PREPARE_PHASE);
                }

                throw reject(
                        REENTRANT_OR_OUT_OF_ORDER,
                        PREPARE_PHASE,
                        "Bootstrap preparation cannot complete from state " + state
                );
            }

            this.session = prepared;
            this.state = BootstrapState.PREPARED;
        }
    }

    AutoconfigureSession beginActivation() {
        synchronized (monitor) {
            state = switch (state) {
                case PREPARED -> BootstrapState.ACTIVATING;

                case NEW -> throw reject(
                        REENTRANT_OR_OUT_OF_ORDER,
                        ACTIVATE_PHASE,
                        """
                        AgentListener.afterAgent invoked without a prior ACP prepare() call \
                        (declarative configuration bypass or missing platform ACP registration)"""
                );

                case PREPARING -> throw reject(
                        REENTRANT_OR_OUT_OF_ORDER,
                        ACTIVATE_PHASE,
                        "afterAgent invoked while bootstrap preparation is still in progress"
                );

                case ACTIVATING, ACTIVE -> throw reject(
                        DUPLICATE_ACTIVATION,
                        ACTIVATE_PHASE,
                        "afterAgent invoked more than once for this coordinator"
                );

                case FAILED, CLOSING, CLOSED -> throw stickyFailure(ACTIVATE_PHASE);
            };

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

    BootstrapFailure failure() {
        synchronized (monitor) {
            if (failure == null) {
                throw new IllegalStateException("Bootstrap failure is not recorded");
            }

            return failure;
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
            if (closeStarted) {
                return CloseTransition.alreadyClosing();
            }

            closeStarted = true;
            if (state != BootstrapState.FAILED) {
                state = BootstrapState.CLOSING;
            }

            BootstrapResources owned = resources;
            resources = null;
            return new CloseTransition(true, owned);
        }
    }

    void markClosed() {
        synchronized (monitor) {
            if (state == BootstrapState.CLOSING) {
                state = BootstrapState.CLOSED;
            }
        }
    }

    void escalateRollbackIncomplete(String phase, Throwable cleanupFailure) {
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(cleanupFailure, "cleanupFailure");

        synchronized (monitor) {
            if (state == BootstrapState.CLOSED) {
                logRejected(FAILED_ROLLBACK_INCOMPLETE, phase, phase, null, cleanupFailure);
                return;
            }

            if (failure != null) {
                BootstrapCleanupFailures.suppressPreservingPrimary(failure.cause(), cleanupFailure);
                if (FAILED_ROLLBACK_INCOMPLETE.equals(failure.effectiveCode())) {
                    logRejected(FAILED_ROLLBACK_INCOMPLETE, phase, phase, null, cleanupFailure);
                    return;
                }
            }

            recordFailure(escalatedFailure(phase, cleanupFailure), phase, null, cleanupFailure);
        }
    }

    BootstrapException fail(String code,
                            String phase,
                            String subphase,
                            String propertyName,
                            Throwable cause) {
        Objects.requireNonNull(cause, "cause");

        synchronized (monitor) {
            if (failure != null) {
                logRejected(code, phase, subphase, propertyName, cause);
                return new BootstrapException(failure);
            }

            if (state == BootstrapState.CLOSED) {
                return rejectWithoutStateChange(code, phase, cause);
            }

            recordFailure(new BootstrapFailure(code, code, phase, cause), subphase, propertyName, cause);
            return new BootstrapException(failure);
        }
    }

    BootstrapException rejectWithoutStateChange(String code, String phase, Throwable cause) {
        logRejected(code, phase, phase, null, cause);
        return new BootstrapException(new BootstrapFailure(code, code, phase, cause));
    }

    BootstrapException stickyFailure(String phase) {
        synchronized (monitor) {
            BootstrapFailure sticky;
            if (failure == null) {
                sticky = new BootstrapFailure(
                        BOOTSTRAP_TERMINAL,
                        BOOTSTRAP_TERMINAL,
                        phase,
                        new IllegalStateException("Bootstrap coordinator is in terminal state " + state)
                );
            } else {
                sticky = new BootstrapFailure(
                        failure.primaryCode(),
                        failure.effectiveCode(),
                        phase,
                        failure.cause()
                );
            }

            return new BootstrapException(sticky);
        }
    }

    private boolean terminal() {
        return state == BootstrapState.FAILED
                || state == BootstrapState.CLOSED
                || state == BootstrapState.CLOSING;
    }

    private void recordFailure(BootstrapFailure next,
                               String subphase,
                               String propertyName,
                               Throwable loggedCause) {
        this.failure = next;
        this.state = BootstrapState.FAILED;
        logFailure(
                BootstrapLogEvent.BOOTSTRAP_PHASE_FAILED,
                next.effectiveCode(),
                next.phase(),
                subphase,
                propertyName,
                loggedCause
        );
    }

    private BootstrapFailure escalatedFailure(String phase, Throwable cleanupFailure) {
        if (failure == null) {
            return new BootstrapFailure(FAILED_ROLLBACK_INCOMPLETE, FAILED_ROLLBACK_INCOMPLETE, phase, cleanupFailure);
        } else {
            return new BootstrapFailure(failure.primaryCode(), FAILED_ROLLBACK_INCOMPLETE, phase, failure.cause());
        }
    }

    private BootstrapException reject(String code, String phase, String message) {
        return rejectWithoutStateChange(code, phase, new IllegalStateException(message));
    }

    private static void logRejected(String code,
                                    String phase,
                                    String subphase,
                                    String propertyName,
                                    Throwable cause) {
        logFailure(BootstrapLogEvent.BOOTSTRAP_OPERATION_REJECTED, code, phase, subphase, propertyName, cause);
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
