package space.br1440.platform.tracing.autoconfigure.agent;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

public final class AgentStartupVerifier {

    private static final Duration STARTUP_READINESS_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration READINESS_POLL_INTERVAL = Duration.ofMillis(25);

    private static final String EXTENSION_READINESS_TIMEOUT = "EXTENSION_READINESS_TIMEOUT";
    private static final String PLATFORM_EXTENSION_MISSING = "PLATFORM_EXTENSION_MISSING";
    private static final String CONTROLLED_AGENT_MISSING = "CONTROLLED_AGENT_MISSING";
    private static final String CONTROLLED_AGENT_FORBIDDEN = "CONTROLLED_AGENT_FORBIDDEN";
    private static final String NO_FAILURE_CODE = "";

    @FunctionalInterface
    interface Sleeper {
        void sleep(long nanos);
    }

    private final AgentReadinessCoherenceGuard coherenceGuard;
    private final Duration startupReadinessTimeout;
    private final LongSupplier nanoTime;
    private final Sleeper sleeper;
    private final Supplier<String> applicationVersion;
    private final AtomicReference<TracingDeploymentDiagnostics> stickyFailure = new AtomicReference<>();

    public AgentStartupVerifier(AgentReadinessCoherenceGuard coherenceGuard) {
        this(coherenceGuard, StarterModuleVersionLoader::starterModuleVersion);
    }

    public AgentStartupVerifier(AgentReadinessCoherenceGuard coherenceGuard, Supplier<String> applicationVersion) {
        this(coherenceGuard,
                STARTUP_READINESS_TIMEOUT,
                System::nanoTime,
                AgentStartupVerifier::parkAndPreserveInterrupt,
                applicationVersion
        );
    }

    AgentStartupVerifier(AgentReadinessCoherenceGuard coherenceGuard,
                         Duration startupReadinessTimeout,
                         LongSupplier nanoTime,
                         Sleeper sleeper,
                         Supplier<String> applicationVersion) {
        this.coherenceGuard = Objects.requireNonNull(coherenceGuard, "coherenceGuard");
        this.startupReadinessTimeout = Objects.requireNonNull(startupReadinessTimeout, "startupReadinessTimeout");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
        this.applicationVersion = Objects.requireNonNull(applicationVersion, "applicationVersion");

        if (startupReadinessTimeout.isNegative() || startupReadinessTimeout.isZero()) {
            throw new IllegalArgumentException("startupReadinessTimeout must be positive");
        }
    }

    private static void parkAndPreserveInterrupt(long nanos) {
        LockSupport.parkNanos(nanos);
        if (Thread.currentThread().isInterrupted()) {
            throw new IllegalStateException("Interrupted while awaiting Controlled Platform Agent readiness decision");
        }
    }

    public TracingDeploymentDiagnostics verify(boolean tracingEnabled,
                                               boolean agentMarkerVisible,
                                               boolean userOpenTelemetryBeanPresent,
                                               boolean globalOpenTelemetrySet) {
        TracingDeploymentDiagnostics diagnostics = stickyFailure.get();
        if (diagnostics == null) {
            Verification verification = new Verification(
                    tracingEnabled,
                    agentMarkerVisible,
                    userOpenTelemetryBeanPresent,
                    globalOpenTelemetrySet
            );
            diagnostics = retainIfSticky(verification.run());
        }

        return enforceContract(tracingEnabled, diagnostics);
    }

    private TracingDeploymentDiagnostics retainIfSticky(TracingDeploymentDiagnostics decided) {
        if (!AgentReadinessClassifier.sticky(decided.runtimeState())) {
            return decided;
        }

        return stickyFailure.updateAndGet(current -> current != null ? current : decided);
    }

    private TracingDeploymentDiagnostics enforceContract(boolean tracingEnabled,
                                                         TracingDeploymentDiagnostics diagnostics) {
        AgentRuntimeState expected = tracingEnabled ? AgentRuntimeState.AGENT_READY : AgentRuntimeState.DISABLED;
        if (diagnostics.runtimeState() != expected) {
            throw new IllegalStateException(
                    AgentContractFailureMessages.reject(
                            tracingEnabled,
                            diagnostics,
                            applicationVersion
                    )
            );
        }

        return diagnostics;
    }

    private sealed interface Verdict {

        record Decided(TracingDeploymentDiagnostics diagnostics) implements Verdict {
        }

        record Pending(Supplier<TracingDeploymentDiagnostics> onTimeout) implements Verdict {
        }
    }

    private final class Verification {

        private final boolean tracingEnabled;
        private final boolean agentMarkerVisible;
        private final boolean userOpenTelemetryBeanPresent;
        private final boolean globalOpenTelemetrySet;
        private final long startedAt;
        private boolean snapshotEverRead;

        Verification(boolean tracingEnabled,
                     boolean agentMarkerVisible,
                     boolean userOpenTelemetryBeanPresent,
                     boolean globalOpenTelemetrySet) {
            this.tracingEnabled = tracingEnabled;
            this.agentMarkerVisible = agentMarkerVisible;
            this.userOpenTelemetryBeanPresent = userOpenTelemetryBeanPresent;
            this.globalOpenTelemetrySet = globalOpenTelemetrySet;
            this.startedAt = nanoTime.getAsLong();
        }

        TracingDeploymentDiagnostics run() {
            while (true) {
                switch (probe()) {
                    case Verdict.Decided decided -> {
                        return decided.diagnostics();
                    }

                    case Verdict.Pending pending -> {
                        long remaining = startupReadinessTimeout.toNanos() - (nanoTime.getAsLong() - startedAt);
                        if (remaining <= 0L) {
                            return pending.onTimeout().get();
                        }

                        sleeper.sleep(Math.min(remaining, READINESS_POLL_INTERVAL.toNanos()));
                    }
                }
            }
        }

        private Verdict probe() {
            boolean endpointPresent = coherenceGuard.endpointRegistered();
            if (dualSdkDetected(endpointPresent)) {
                return decided(withoutSnapshot(
                        AgentRuntimeState.DUAL_SDK_DETECTED, endpointPresent, AgentNotReadyReasonCodes.DUAL_SDK_COLLISION));
            }

            if (!endpointPresent) {
                return endpointAbsent();
            }

            AgentReadinessProbe.ProbeResult result = coherenceGuard.readStableSnapshot();
            if (result instanceof AgentReadinessProbe.ProbeResult.Present(AgentReadinessSnapshot snapshot)) {
                return snapshotRead(snapshot);
            }

            return snapshotRejected(AgentReadinessProbe.ProbeResult.reasonOf(result));
        }

        private Verdict endpointAbsent() {
            if (disabledWithoutAgentSignals()) {
                return decided(disabled());
            }

            if (tracingEnabled && agentMarkerVisible) {
                return pending(this::missing);
            }

            return decided(missing());
        }

        private Verdict snapshotRead(AgentReadinessSnapshot snapshot) {
            snapshotEverRead = true;
            AgentReadinessClassifier.Decision decision = AgentReadinessClassifier.decide(snapshot, applicationVersion.get());
            AgentRuntimeState state = decision.state();

            if (tracingEnabled && state == AgentRuntimeState.EXTENSION_INITIALIZING) {
                return pending(() -> fromSnapshot(state, snapshot, EXTENSION_READINESS_TIMEOUT));
            }

            if (tracingEnabled && state == AgentRuntimeState.AGENT_READY) {
                try {
                    coherenceGuard.pin(snapshot);
                } catch (AgentReadinessCoherenceException coherenceFailure) {
                    return decided(fromSnapshot(
                            AgentRuntimeState.EXTENSION_INCOMPATIBLE,
                            snapshot,
                            coherenceFailure.failureCode()
                    ));
                }
            }

            return decided(fromSnapshot(state, snapshot, snapshotFailureCode(snapshot, decision)));
        }

        private Verdict snapshotRejected(AgentReadinessProbe.ProbeReason reason) {
            if (reason == AgentReadinessProbe.ProbeReason.MIXED_GENERATION && tracingEnabled) {
                return pending(() -> withoutSnapshot(AgentRuntimeState.EXTENSION_INCOMPATIBLE, true, reason.failureCode()));
            }

            if (reason == AgentReadinessProbe.ProbeReason.ENDPOINT_DISAPPEARED && !snapshotEverRead) {
                return decided(disabledWithoutAgentSignals() ? disabled() : missing());
            }

            return decided(withoutSnapshot(AgentRuntimeState.EXTENSION_INCOMPATIBLE, true, reason.failureCode()));
        }

        private boolean dualSdkDetected(boolean endpointPresent) {
            return userOpenTelemetryBeanPresent || (globalOpenTelemetrySet && !agentMarkerVisible && !endpointPresent);
        }

        private boolean disabledWithoutAgentSignals() {
            return !tracingEnabled && !agentMarkerVisible && !globalOpenTelemetrySet;
        }

        private String snapshotFailureCode(AgentReadinessSnapshot snapshot, AgentReadinessClassifier.Decision decision) {
            if (!tracingEnabled && decision.state() != AgentRuntimeState.DISABLED) {
                return CONTROLLED_AGENT_FORBIDDEN;
            }

            if (decision.state() == AgentRuntimeState.EXTENSION_FAILED) {
                return snapshot.lifecycleState().failureCode();
            }

            return decision.reasonCode();
        }

        private TracingDeploymentDiagnostics missing() {
            return agentMarkerVisible
                    ? withoutSnapshot(AgentRuntimeState.EXTENSION_MISSING, false, PLATFORM_EXTENSION_MISSING)
                    : withoutSnapshot(AgentRuntimeState.AGENT_MISSING, false, CONTROLLED_AGENT_MISSING);
        }

        private TracingDeploymentDiagnostics withoutSnapshot(AgentRuntimeState state,
                                                             boolean endpointPresent,
                                                             String failureCode) {
            return endpointPresent
                    ? TracingDeploymentDiagnostics.withUnreadableAgentData(tracingEnabled, agentMarkerVisible, state, failureCode)
                    : TracingDeploymentDiagnostics.withoutAgentData(tracingEnabled, agentMarkerVisible, state, failureCode);
        }

        private TracingDeploymentDiagnostics fromSnapshot(AgentRuntimeState state,
                                                          AgentReadinessSnapshot snapshot,
                                                          String failureCode) {
            return TracingDeploymentDiagnostics.withAgentData(tracingEnabled, agentMarkerVisible, state, snapshot, failureCode);
        }

        private static TracingDeploymentDiagnostics disabled() {
            return TracingDeploymentDiagnostics.withoutAgentData(false, false, AgentRuntimeState.DISABLED, NO_FAILURE_CODE);
        }

        private static Verdict decided(TracingDeploymentDiagnostics diagnostics) {
            return new Verdict.Decided(diagnostics);
        }

        private static Verdict pending(Supplier<TracingDeploymentDiagnostics> onTimeout) {
            return new Verdict.Pending(onTimeout);
        }
    }
}
