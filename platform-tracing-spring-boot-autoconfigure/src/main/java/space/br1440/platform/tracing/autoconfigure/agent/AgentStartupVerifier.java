package space.br1440.platform.tracing.autoconfigure.agent;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.LockSupport;
import java.util.function.LongSupplier;
import javax.management.MBeanServer;
import javax.management.ObjectName;

import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.PlatformTracingJmxObjectNames;

public final class AgentStartupVerifier {

    private static final Duration STARTUP_READINESS_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration READINESS_POLL_INTERVAL = Duration.ofMillis(25);

    @FunctionalInterface
    interface Sleeper {
        void sleep(long nanos);
    }

    private final AgentReadinessProbe probe;
    private final Duration startupReadinessTimeout;
    private final LongSupplier nanoTime;
    private final Sleeper sleeper;
    private final String applicationVersion;

    public AgentStartupVerifier() {
        this(ManagementFactory.getPlatformMBeanServer(), STARTUP_READINESS_TIMEOUT, System::nanoTime,
                AgentStartupVerifier::parkAndPreserveInterrupt, resolveApplicationVersion());
    }

    public AgentStartupVerifier(MBeanServer server, String applicationVersion) {
        this(server, STARTUP_READINESS_TIMEOUT, System::nanoTime,
                AgentStartupVerifier::parkAndPreserveInterrupt, applicationVersion);
    }

    AgentStartupVerifier(MBeanServer server,
                         Duration startupReadinessTimeout,
                         LongSupplier nanoTime,
                         Sleeper sleeper,
                         String applicationVersion) {
        this.probe = new AgentReadinessProbe(Objects.requireNonNull(server, "server"));
        this.startupReadinessTimeout = Objects.requireNonNull(startupReadinessTimeout, "startupReadinessTimeout");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
        this.applicationVersion = applicationVersion;

        if (startupReadinessTimeout.isNegative() || startupReadinessTimeout.isZero()) {
            throw new IllegalArgumentException("startupReadinessTimeout must be positive");
        }
    }

    private static String resolveApplicationVersion() {
        Package thisPackage = AgentStartupVerifier.class.getPackage();
        return (thisPackage == null) ? null : thisPackage.getImplementationVersion();
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
        TracingDeploymentDiagnostics diagnostics = decide(tracingEnabled, agentMarkerVisible, userOpenTelemetryBeanPresent, globalOpenTelemetrySet);
        return enforceContract(tracingEnabled, diagnostics);
    }

    private TracingDeploymentDiagnostics decide(boolean tracingEnabled,
                                                boolean agentMarkerVisible,
                                                boolean userOpenTelemetryBeanPresent,
                                                boolean globalOpenTelemetrySet) {
        ObjectName readinessName = PlatformTracingJmxObjectNames.EXTENSION_READINESS;
        long start = nanoTime.getAsLong();
        boolean everSucceeded = false;

        while (true) {
            boolean endpointPresent = probe.endpointRegistered(readinessName);

            if (userOpenTelemetryBeanPresent
                    || (globalOpenTelemetrySet && !agentMarkerVisible && !endpointPresent)) {
                return terminalWithoutSnapshot(tracingEnabled, AgentRuntimeState.DUAL_SDK_DETECTED,
                        agentMarkerVisible, endpointPresent, "APPLICATION_RUNTIME_DETECTED");
            }

            if (!endpointPresent) {
                if (!tracingEnabled && isAbsentSignals(agentMarkerVisible, userOpenTelemetryBeanPresent, globalOpenTelemetrySet)) {
                    return disabled();
                }

                return missing(tracingEnabled, agentMarkerVisible);
            }

            AgentReadinessProbe.ProbeResult result = probe.read(readinessName);
            if (result instanceof AgentReadinessProbe.ProbeResult.Present(AgentReadinessSnapshot snapshot)) {
                everSucceeded = true;
                AgentRuntimeState state = AgentReadinessClassifier.classify(snapshot, applicationVersion);

                if (tracingEnabled && state == AgentRuntimeState.EXTENSION_INITIALIZING) {
                    long elapsed = (nanoTime.getAsLong() - start);
                    if (elapsed >= startupReadinessTimeout.toNanos()) {
                        return terminalFromSnapshot(
                                true, state,
                                agentMarkerVisible, snapshot, "EXTENSION_READINESS_TIMEOUT"
                        );
                    }

                    sleeper.sleep(pollDelay(elapsed));
                    continue;
                }

                return terminalFromSnapshot(
                        tracingEnabled, state,
                        agentMarkerVisible, snapshot, snapshot.status().failureCode()
                );
            }

            AgentReadinessProbe.ProbeReason reason = reasonOf(result);
            if (reason == AgentReadinessProbe.ProbeReason.ENDPOINT_DISAPPEARED && !everSucceeded) {
                if (!tracingEnabled && isAbsentSignals(agentMarkerVisible, userOpenTelemetryBeanPresent, globalOpenTelemetrySet)) {
                    return disabled();
                }

                return missing(tracingEnabled, agentMarkerVisible);
            }

            return terminalWithoutSnapshot(
                    tracingEnabled, AgentRuntimeState.EXTENSION_INCOMPATIBLE,
                    agentMarkerVisible, true, failureCodeFor(reason)
            );
        }
    }

    private long pollDelay(long elapsed) {
        long remaining = startupReadinessTimeout.toNanos() - elapsed;
        return Math.min(remaining, READINESS_POLL_INTERVAL.toNanos());
    }

    private static boolean isAbsentSignals(boolean agentMarkerVisible,
                                           boolean userOpenTelemetryBeanPresent,
                                           boolean globalOpenTelemetrySet) {
        return !agentMarkerVisible && !userOpenTelemetryBeanPresent && !globalOpenTelemetrySet;
    }

    private static TracingDeploymentDiagnostics disabled() {
        return terminalWithoutSnapshot(false, AgentRuntimeState.DISABLED, false, false, "");
    }

    private static TracingDeploymentDiagnostics missing(boolean tracingEnabled, boolean agentMarkerVisible) {
        AgentRuntimeState state = agentMarkerVisible
                ? AgentRuntimeState.EXTENSION_MISSING
                : AgentRuntimeState.AGENT_MISSING;
        String failureCode = agentMarkerVisible ? "PLATFORM_EXTENSION_MISSING" : "CONTROLLED_AGENT_MISSING";
        return terminalWithoutSnapshot(tracingEnabled, state, agentMarkerVisible, false, failureCode);
    }

    private static AgentReadinessProbe.ProbeReason reasonOf(AgentReadinessProbe.ProbeResult result) {
        return switch (result) {
            case AgentReadinessProbe.ProbeResult.Rejected rejected -> rejected.reason();
            case AgentReadinessProbe.ProbeResult.Indeterminate indeterminate -> indeterminate.reason();
            case AgentReadinessProbe.ProbeResult.Present ignored ->
                    throw new IllegalStateException("Present is handled by the caller before reasonOf is consulted");
        };
    }

    private static String failureCodeFor(AgentReadinessProbe.ProbeReason reason) {
        return switch (reason) {
            case ENDPOINT_DISAPPEARED -> "READINESS_ENDPOINT_DISAPPEARED";
            case ACCESS_DENIED -> "READINESS_ACCESS_DENIED";
            case INVOCATION_FAILED -> "READINESS_INVOCATION_FAILED";
            case INCOMPLETE_DESCRIPTOR, MALFORMED_DESCRIPTOR -> "MALFORMED_READINESS_DESCRIPTOR";
        };
    }

    private static TracingDeploymentDiagnostics terminalFromSnapshot(boolean tracingEnabled,
                                                                      AgentRuntimeState state,
                                                                      boolean agentMarkerVisible,
                                                                      AgentReadinessSnapshot snapshot,
                                                                      String failureCode) {
        return new TracingDeploymentDiagnostics(
                tracingEnabled,
                agentMarkerVisible,
                state,
                new TracingDeploymentDiagnostics.AgentReadiness(
                        true,
                        snapshot.protocol().extensionVersion(),
                        snapshot.protocol().protocolVersion(),
                        snapshot.status().lifecycle(),
                        failureCode,
                        snapshot.capabilities().advertised()
                )
        );
    }

    private static TracingDeploymentDiagnostics terminalWithoutSnapshot(boolean tracingEnabled,
                                                                        AgentRuntimeState state,
                                                                        boolean agentMarkerVisible,
                                                                        boolean readinessEndpointPresent,
                                                                        String failureCode) {
        if (!readinessEndpointPresent) {
            return TracingDeploymentDiagnostics.withoutAgentData(tracingEnabled, agentMarkerVisible, state, failureCode);
        }

        return new TracingDeploymentDiagnostics(
                tracingEnabled,
                agentMarkerVisible,
                state,
                new TracingDeploymentDiagnostics.AgentReadiness(
                        true, "", -1, "", failureCode, Set.of()
                )
        );
    }

    private static TracingDeploymentDiagnostics enforceContract(boolean tracingEnabled,
                                                                TracingDeploymentDiagnostics diagnostics) {
        AgentRuntimeState expected = tracingEnabled ? AgentRuntimeState.AGENT_READY : AgentRuntimeState.DISABLED;
        if (diagnostics.runtimeState() != expected) {
            String failureCode = diagnostics.agentReadiness().failureCode().isBlank()
                    ? "NONE"
                    : diagnostics.agentReadiness().failureCode();

            throw new IllegalStateException(
                    "platform.tracing.enabled=" + tracingEnabled + " rejected observed runtime state="
                            + diagnostics.runtimeState() + ", failureCode=" + failureCode
            );
        }

        return diagnostics;
    }
}
