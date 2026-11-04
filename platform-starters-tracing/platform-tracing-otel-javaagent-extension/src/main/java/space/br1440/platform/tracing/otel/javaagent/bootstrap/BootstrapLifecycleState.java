package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapFailureCodes.BOOTSTRAP_TERMINAL;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapFailureCodes.DUPLICATE_ACTIVATION;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapFailureCodes.FAILED_ROLLBACK_INCOMPLETE;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapFailureCodes.PLATFORM_ACP_NOT_EXECUTED;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapFailureCodes.REENTRANT_OR_OUT_OF_ORDER;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapFailureCodes.SECOND_SDK_BUILD;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapFailureCodes.SHUTDOWN_CLEANUP_FAILED;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;

import space.br1440.platform.tracing.otel.javaagent.bootstrap.resources.BootstrapCleanupFailures;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.resources.BootstrapResources;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.session.AutoconfigureSession;
import space.br1440.platform.tracing.otel.javaagent.jmx.readiness.ExtensionRuntimeEvidence;

final class BootstrapLifecycleState {

    private static final String PREPARE_PHASE = "prepare";
    private static final String ACTIVATE_PHASE = "activate";

    private final Object monitor = new Object();

    private BootstrapState state = BootstrapState.NEW;
    private AutoconfigureSession session;
    private ExtensionRuntimeEvidence evidence;
    private BootstrapFailure failure;
    private BootstrapResources resources;
    private CloseFlight closeFlight;

    Object monitor() {
        return monitor;
    }

    void beginPrepare() {
        synchronized (monitor) {
            if (terminal()) {
                throw new BootstrapException(stickyFailure(PREPARE_PHASE));
            }

            state = switch (state) {
                case NEW -> BootstrapState.PREPARING;

                case PREPARING -> throw new BootstrapAdmissionRejectedException(reject(
                        REENTRANT_OR_OUT_OF_ORDER,
                        PREPARE_PHASE,
                        "Bootstrap preparation is already in progress"
                ));

                default -> throw new BootstrapAdmissionRejectedException(reject(
                        SECOND_SDK_BUILD,
                        PREPARE_PHASE,
                        """
                        Bootstrap coordinator already prepared/activated for this JVM; \
                        a second AutoConfiguredOpenTelemetrySdk build is not supported"""
                ));
            };
        }
    }

    void completePrepare(AutoconfigureSession prepared) {
        Objects.requireNonNull(prepared, "prepared");

        synchronized (monitor) {
            if (state != BootstrapState.PREPARING) {
                if (failure != null) {
                    throw new BootstrapException(stickyFailure(PREPARE_PHASE));
                }

                throw new BootstrapAdmissionRejectedException(reject(
                        REENTRANT_OR_OUT_OF_ORDER,
                        PREPARE_PHASE,
                        "Bootstrap preparation cannot complete from state " + state
                ));
            }

            this.evidence = prepared.evidence();
            this.session = prepared;
            this.state = BootstrapState.PREPARED;
        }
    }

    AutoconfigureSession beginActivation() {
        synchronized (monitor) {
            state = switch (state) {
                case PREPARED -> BootstrapState.ACTIVATING;

                case NEW -> throw new BootstrapAdmissionRejectedException(reject(
                        REENTRANT_OR_OUT_OF_ORDER,
                        ACTIVATE_PHASE,
                        """
                        AgentListener.afterAgent invoked without a prior ACP prepare() call \
                        (declarative configuration bypass or missing platform ACP registration)"""
                ));

                case PREPARING -> throw new BootstrapAdmissionRejectedException(reject(
                        REENTRANT_OR_OUT_OF_ORDER,
                        ACTIVATE_PHASE,
                        "afterAgent invoked while bootstrap preparation is still in progress"
                ));

                case ACTIVATING, ACTIVE -> throw new BootstrapAdmissionRejectedException(reject(
                        DUPLICATE_ACTIVATION,
                        ACTIVATE_PHASE,
                        "afterAgent invoked more than once for this coordinator"
                ));

                case FAILED, CLOSING, CLOSED -> throw new BootstrapException(stickyFailure(ACTIVATE_PHASE));
            };

            return session;
        }
    }

    void requireOperable(String phase) {
        synchronized (monitor) {
            if (terminal()) {
                throw new BootstrapException(stickyFailure(phase));
            }
        }
    }

    AutoconfigureSession requireSession() {
        synchronized (monitor) {
            if (session == null) {
                throw new BootstrapException(stickyFailure("session"));
            }

            return session;
        }
    }

    ExtensionRuntimeEvidence currentEvidence() {
        synchronized (monitor) {
            return evidence;
        }
    }

    BootstrapState state() {
        synchronized (monitor) {
            return state;
        }
    }

    BootstrapDiagnosticSnapshot diagnosticSnapshot() {
        synchronized (monitor) {
            return new BootstrapDiagnosticSnapshot(state, failure);
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

    void commitActive() {
        synchronized (monitor) {
            this.state = BootstrapState.ACTIVE;
        }
    }

    void attachResources(BootstrapResources activationResources) {
        Objects.requireNonNull(activationResources, "activationResources");

        synchronized (monitor) {
            this.resources = activationResources;
            this.session = null;
        }
    }

    CloseAcquisition acquireClose() {
        synchronized (monitor) {
            if (closeFlight != null) {
                if (closeFlight.ownedByCurrentThread() && closeFlight.inProgress()) {
                    return CloseAcquisition.reentrant(closeFlight);
                }

                return CloseAcquisition.follower(closeFlight);
            }

            if (state != BootstrapState.FAILED) {
                state = BootstrapState.CLOSING;
            }

            BootstrapResources owned = resources;
            resources = null;
            CloseContext context = new CloseContext(failure, owned, session);
            session = null;
            closeFlight = new CloseFlight(Thread.currentThread(), context);
            return CloseAcquisition.owner(closeFlight);
        }
    }

    void completeClose(CloseFlight flight, Throwable outcome) {
        Objects.requireNonNull(flight, "flight");

        synchronized (monitor) {
            if (closeFlight != flight) {
                throw new IllegalStateException("Bootstrap close flight is not owned by this lifecycle");
            }
        }

        flight.complete(outcome);
    }

    void markClosed() {
        synchronized (monitor) {
            if (state == BootstrapState.CLOSING) {
                state = BootstrapState.CLOSED;
            }
        }
    }

    BootstrapFailure classifyCloseFailure(CloseContext context, Throwable cleanupFailure) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(cleanupFailure, "cleanupFailure");

        BootstrapFailure priorFailure = context.failureBeforeClose();
        if (priorFailure == null) {
            return new BootstrapFailure(
                    SHUTDOWN_CLEANUP_FAILED,
                    SHUTDOWN_CLEANUP_FAILED,
                    "close",
                    cleanupFailure
            );
        }

        return new BootstrapFailure(
                priorFailure.primaryCode(),
                FAILED_ROLLBACK_INCOMPLETE,
                "close",
                priorFailure.cause()
        );
    }

    BootstrapFailure recordCloseFailure(CloseContext context, Throwable cleanupFailure) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(cleanupFailure, "cleanupFailure");
        requireMonitorHeld("Close failure recording");

        BootstrapFailure currentFailure = failure;
        if (currentFailure == null) {
            failure = classifyCloseFailure(context, cleanupFailure);
        } else {
            BootstrapCleanupFailures.suppressPreservingPrimary(currentFailure.cause(), cleanupFailure);
            failure = new BootstrapFailure(
                    currentFailure.primaryCode(),
                    FAILED_ROLLBACK_INCOMPLETE,
                    "close",
                    currentFailure.cause()
            );
        }

        state = BootstrapState.FAILED;
        return failure;
    }

    boolean closeFailureAlreadyRecorded(Throwable cleanupFailure) {
        Objects.requireNonNull(cleanupFailure, "cleanupFailure");
        requireMonitorHeld("Close failure check");

        return failure != null
                && FAILED_ROLLBACK_INCOMPLETE.equals(failure.effectiveCode())
                && BootstrapCleanupFailures.contains(failure.cause(), cleanupFailure);
    }

    /**
     * Эскалирует отказ до {@code FAILED_ROLLBACK_INCOMPLETE}. Throwable-граф не инспектируется:
     * прикрепление cleanup failure к cause выполняет settlement после release.
     */
    FailureAdmission escalateRollbackIncomplete(String phase, Throwable cleanupFailure) {
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(cleanupFailure, "cleanupFailure");

        synchronized (monitor) {
            if (state == BootstrapState.CLOSED) {
                return FailureAdmission.rejected(new BootstrapFailure(
                        FAILED_ROLLBACK_INCOMPLETE,
                        FAILED_ROLLBACK_INCOMPLETE,
                        phase,
                        cleanupFailure
                ));
            }

            if (failure != null && FAILED_ROLLBACK_INCOMPLETE.equals(failure.effectiveCode())) {
                return FailureAdmission.alreadyFailed(failure);
            }

            failure = escalatedFailure(phase, cleanupFailure);
            state = BootstrapState.FAILED;
            return FailureAdmission.recorded(failure, null);
        }
    }

    FailureAdmission fail(String code, String phase, Throwable cause) {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(cause, "cause");

        synchronized (monitor) {
            if (failure != null) {
                return FailureAdmission.alreadyFailed(failure);
            }

            if (state == BootstrapState.CLOSING || state == BootstrapState.CLOSED) {
                return FailureAdmission.rejected(new BootstrapFailure(code, code, phase, cause));
            }

            return record(new BootstrapFailure(code, code, phase, cause));
        }
    }

    /**
     * Записывает отказ, только если lifecycle находится в {@code PREPARED}; иначе состояние не меняется.
     */
    Optional<FailureAdmission> failPrepared(String code, String phase, Throwable cause) {
        return failFrom(BootstrapState.PREPARED, code, phase, cause);
    }

    /**
     * Записывает {@code PLATFORM_ACP_NOT_EXECUTED}, только если prepare ещё не начинался.
     */
    Optional<FailureAdmission> recordMissingPreparation(Throwable cause) {
        return failFrom(BootstrapState.NEW, PLATFORM_ACP_NOT_EXECUTED, ACTIVATE_PHASE, cause);
    }

    private Optional<FailureAdmission> failFrom(BootstrapState expected,
                                                String code,
                                                String phase,
                                                Throwable cause) {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(cause, "cause");

        synchronized (monitor) {
            if (state != expected) {
                return Optional.empty();
            }

            return Optional.of(record(new BootstrapFailure(code, code, phase, cause)));
        }
    }

    private BootstrapFailure stickyFailure(String phase) {
        if (failure == null) {
            return new BootstrapFailure(
                    BOOTSTRAP_TERMINAL,
                    BOOTSTRAP_TERMINAL,
                    phase,
                    new IllegalStateException("Bootstrap coordinator is in terminal state " + state)
            );
        }

        return new BootstrapFailure(
                failure.primaryCode(),
                failure.effectiveCode(),
                phase,
                failure.cause()
        );
    }

    private boolean terminal() {
        return state == BootstrapState.FAILED
                || state == BootstrapState.CLOSED
                || state == BootstrapState.CLOSING;
    }

    private void requireMonitorHeld(String operation) {
        if (!Thread.holdsLock(monitor)) {
            throw new IllegalMonitorStateException(operation + " requires the bootstrap lifecycle monitor");
        }
    }

    /**
     * Фиксирует первый отказ и в той же критической секции изымает session, ещё не переданную ни
     * close-flight, ни стеку activation: её release принадлежит вызывающему settlement.
     */
    private FailureAdmission record(BootstrapFailure next) {
        failure = next;
        state = BootstrapState.FAILED;

        AutoconfigureSession claimed = null;
        if (closeFlight == null && resources == null) {
            claimed = session;
            session = null;
        }

        return FailureAdmission.recorded(next, claimed);
    }

    private BootstrapFailure escalatedFailure(String phase, Throwable cleanupFailure) {
        if (failure == null) {
            return new BootstrapFailure(FAILED_ROLLBACK_INCOMPLETE, FAILED_ROLLBACK_INCOMPLETE, phase, cleanupFailure);
        } else {
            return new BootstrapFailure(failure.primaryCode(), FAILED_ROLLBACK_INCOMPLETE, phase, failure.cause());
        }
    }

    private static BootstrapFailure reject(String code, String phase, String message) {
        return new BootstrapFailure(code, code, phase, new IllegalStateException(message));
    }

    record CloseContext(BootstrapFailure failureBeforeClose,
                        BootstrapResources resources,
                        AutoconfigureSession session) {
    }

    record CloseAcquisition(boolean owner, boolean reentrant, CloseFlight flight) {

        CloseAcquisition {
            Objects.requireNonNull(flight, "flight");
        }

        static CloseAcquisition owner(CloseFlight flight) {
            return new CloseAcquisition(true, false, flight);
        }

        static CloseAcquisition follower(CloseFlight flight) {
            return new CloseAcquisition(false, false, flight);
        }

        static CloseAcquisition reentrant(CloseFlight flight) {
            return new CloseAcquisition(false, true, flight);
        }

        CloseContext context() {
            return flight.context();
        }

        Throwable awaitOutcome() {
            return flight.awaitOutcome();
        }
    }

    static final class CloseFlight {

        private final Thread owner;
        private final CloseContext context;
        private final CountDownLatch completion = new CountDownLatch(1);

        private volatile Throwable outcome;

        private CloseFlight(Thread owner, CloseContext context) {
            this.owner = Objects.requireNonNull(owner, "owner");
            this.context = Objects.requireNonNull(context, "context");
        }

        CloseContext context() {
            return context;
        }

        private boolean ownedByCurrentThread() {
            return owner == Thread.currentThread();
        }

        private boolean inProgress() {
            return completion.getCount() != 0L;
        }

        private void complete(Throwable completedOutcome) {
            if (!ownedByCurrentThread()) {
                throw new IllegalStateException("Only the bootstrap close owner can complete the close flight");
            }
            if (!inProgress()) {
                throw new IllegalStateException("Bootstrap close flight is already complete");
            }

            outcome = completedOutcome;
            completion.countDown();
        }

        private Throwable awaitOutcome() {
            boolean interrupted = false;
            while (true) {
                try {
                    completion.await();
                    break;
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }

            if (interrupted) {
                Thread.currentThread().interrupt();
            }

            return outcome;
        }
    }
}
