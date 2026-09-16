package space.br1440.platform.tracing.autoconfigure.agent;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import javax.management.MBeanServer;

import space.br1440.platform.common.util.StringUtils;

public final class AgentStartupVerifier {

    private static final Duration STARTUP_READINESS_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration READINESS_POLL_INTERVAL = Duration.ofMillis(25);

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

    public AgentStartupVerifier() {
        this(new AgentReadinessCoherenceGuard(),
                STARTUP_READINESS_TIMEOUT,
                System::nanoTime,
                AgentStartupVerifier::parkAndPreserveInterrupt,
                StarterModuleVersionLoader::starterModuleVersion
        );
    }

    public AgentStartupVerifier(MBeanServer server, String applicationVersion) {
        this(new AgentReadinessCoherenceGuard(server),
                STARTUP_READINESS_TIMEOUT,
                System::nanoTime,
                AgentStartupVerifier::parkAndPreserveInterrupt,
                () -> applicationVersion
        );
    }

    public AgentStartupVerifier(AgentReadinessCoherenceGuard coherenceGuard) {
        this(coherenceGuard,
                STARTUP_READINESS_TIMEOUT,
                System::nanoTime,
                AgentStartupVerifier::parkAndPreserveInterrupt,
                StarterModuleVersionLoader::starterModuleVersion
        );
    }

    public AgentStartupVerifier(AgentReadinessCoherenceGuard coherenceGuard,
                                String applicationVersion) {
        this(coherenceGuard,
                STARTUP_READINESS_TIMEOUT,
                System::nanoTime,
                AgentStartupVerifier::parkAndPreserveInterrupt,
                () -> applicationVersion
        );
    }

    AgentStartupVerifier(MBeanServer server,
                         Duration startupReadinessTimeout,
                         LongSupplier nanoTime,
                         Sleeper sleeper,
                         String applicationVersion) {
        this(new AgentReadinessCoherenceGuard(server),
                startupReadinessTimeout,
                nanoTime,
                sleeper,
                () -> applicationVersion
        );
    }

    AgentStartupVerifier(AgentReadinessCoherenceGuard coherenceGuard, Supplier<String> applicationVersion) {
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
                         String applicationVersion) {
        this(coherenceGuard,
                startupReadinessTimeout,
                nanoTime,
                sleeper,
                () -> applicationVersion
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
        TracingDeploymentDiagnostics retainedFailure = stickyFailure.get();
        if (retainedFailure != null) {
            return enforceContract(tracingEnabled, retainedFailure, applicationVersion);
        }

        TracingDeploymentDiagnostics diagnostics = decide(
                tracingEnabled,
                agentMarkerVisible,
                userOpenTelemetryBeanPresent,
                globalOpenTelemetrySet
        );

        if (isStickyFailure(diagnostics.runtimeState())) {
            stickyFailure.compareAndSet(null, diagnostics);
            diagnostics = stickyFailure.get();
        }

        return enforceContract(tracingEnabled, diagnostics, applicationVersion);
    }

    private TracingDeploymentDiagnostics decide(boolean tracingEnabled,
                                                boolean agentMarkerVisible,
                                                boolean userOpenTelemetryBeanPresent,
                                                boolean globalOpenTelemetrySet) {
        long start = nanoTime.getAsLong();
        boolean everSucceeded = false;

        while (true) {
            boolean endpointPresent = coherenceGuard.endpointRegistered();
            if (userOpenTelemetryBeanPresent
                    || (globalOpenTelemetrySet && !agentMarkerVisible && !endpointPresent)) {
                return terminalWithoutSnapshot(
                        tracingEnabled,
                        AgentRuntimeState.DUAL_SDK_DETECTED,
                        agentMarkerVisible,
                        endpointPresent,
                        "DUAL_SDK_COLLISION"
                );
            }

            if (!endpointPresent) {
                if (!tracingEnabled
                        && isAbsentSignals(agentMarkerVisible, userOpenTelemetryBeanPresent, globalOpenTelemetrySet)) {
                    return disabled();
                }

                if (tracingEnabled && agentMarkerVisible) {
                    long elapsed = nanoTime.getAsLong() - start;
                    if (elapsed < startupReadinessTimeout.toNanos()) {
                        sleeper.sleep(pollDelay(elapsed));
                        continue;
                    }
                }

                return missing(tracingEnabled, agentMarkerVisible);
            }

            AgentReadinessProbe.ProbeResult result = readCoherent();
            if (result instanceof AgentReadinessProbe.ProbeResult.Present(AgentReadinessSnapshot snapshot)) {
                everSucceeded = true;
                AgentReadinessClassifier.Decision decision = AgentReadinessClassifier.decide(snapshot, applicationVersion.get());
                AgentRuntimeState state = decision.state();

                if (tracingEnabled && state == AgentRuntimeState.EXTENSION_INITIALIZING) {
                    long elapsed = nanoTime.getAsLong() - start;
                    if (elapsed >= startupReadinessTimeout.toNanos()) {
                        return terminalFromSnapshot(
                                true,
                                state,
                                agentMarkerVisible,
                                snapshot,
                                "EXTENSION_READINESS_TIMEOUT");
                    }

                    sleeper.sleep(pollDelay(elapsed));
                    continue;
                }

                if (tracingEnabled && state == AgentRuntimeState.AGENT_READY) {
                    try {
                        coherenceGuard.pin(snapshot);
                    } catch (AgentReadinessCoherenceException coherenceFailure) {
                        return terminalFromSnapshot(
                                true,
                                AgentRuntimeState.EXTENSION_INCOMPATIBLE,
                                agentMarkerVisible,
                                snapshot,
                                coherenceFailure.failureCode());
                    }
                }

                return terminalFromSnapshot(
                        tracingEnabled,
                        state,
                        agentMarkerVisible,
                        snapshot,
                        resolveSnapshotFailureCode(tracingEnabled, snapshot, decision)
                );
            }

            AgentReadinessProbe.ProbeReason reason = AgentReadinessProbe.ProbeResult.reasonOf(result);
            if (reason == AgentReadinessProbe.ProbeReason.MIXED_GENERATION && tracingEnabled) {
                long elapsed = nanoTime.getAsLong() - start;
                if (elapsed >= startupReadinessTimeout.toNanos()) {
                    return terminalWithoutSnapshot(
                            true,
                            AgentRuntimeState.EXTENSION_INCOMPATIBLE,
                            agentMarkerVisible,
                            true,
                            reason.failureCode());
                }

                sleeper.sleep(pollDelay(elapsed));
                continue;
            }

            if (reason == AgentReadinessProbe.ProbeReason.ENDPOINT_DISAPPEARED && !everSucceeded) {
                if (!tracingEnabled
                        && isAbsentSignals(agentMarkerVisible, userOpenTelemetryBeanPresent, globalOpenTelemetrySet)) {
                    return disabled();
                }

                return missing(tracingEnabled, agentMarkerVisible);
            }

            return terminalWithoutSnapshot(
                    tracingEnabled,
                    AgentRuntimeState.EXTENSION_INCOMPATIBLE,
                    agentMarkerVisible,
                    true,
                    reason.failureCode()
            );
        }
    }

    private AgentReadinessProbe.ProbeResult readCoherent() {
        AgentReadinessProbe.ProbeResult first = coherenceGuard.readSnapshot();
        if (!(first instanceof AgentReadinessProbe.ProbeResult.Present(AgentReadinessSnapshot firstSnapshot))) {
            return first;
        }

        AgentReadinessProbe.ProbeResult second = coherenceGuard.readSnapshot();
        if (!(second instanceof AgentReadinessProbe.ProbeResult.Present(AgentReadinessSnapshot secondSnapshot))) {
            return second;
        }

        if (!firstSnapshot.anchorIdentity().equals(secondSnapshot.anchorIdentity())
                || !firstSnapshot.equals(secondSnapshot)) {
            return new AgentReadinessProbe.ProbeResult.Rejected(
                    AgentReadinessProbe.ProbeReason.MIXED_GENERATION);
        }

        return second;
    }

    private long pollDelay(long elapsed) {
        long remaining = startupReadinessTimeout.toNanos() - elapsed;
        return Math.min(remaining, READINESS_POLL_INTERVAL.toNanos());
    }

    private static boolean isStickyFailure(AgentRuntimeState state) {
        return switch (state) {
            case EXTENSION_INCOMPATIBLE, EXTENSION_FAILED, CAPABILITY_MISSING, DUAL_SDK_DETECTED -> true;
            default -> false;
        };
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

    private static String resolveSnapshotFailureCode(boolean tracingEnabled,
                                                     AgentReadinessSnapshot snapshot,
                                                     AgentReadinessClassifier.Decision decision) {
        if (!tracingEnabled && decision.state() != AgentRuntimeState.DISABLED) {
            return "CONTROLLED_AGENT_FORBIDDEN";
        }

        String resolved = firstNonBlank(snapshot.lifecycleState().failureCode(), decision.reasonCode());
        if (StringUtils.hasText(resolved)) {
            return resolved;
        }

        return "";
    }

    private static String firstNonBlank(String first, String second) {
        if (StringUtils.hasText(first)) {
            return first;
        }

        return (second == null) ? "" : second;
    }

    private static TracingDeploymentDiagnostics terminalFromSnapshot(boolean tracingEnabled,
                                                                      AgentRuntimeState state,
                                                                      boolean agentMarkerVisible,
                                                                      AgentReadinessSnapshot snapshot,
                                                                      String failureCode) {
        AgentReadinessSnapshot.ContractIdentity contractIdentity = snapshot.contractIdentity();
        AgentReadinessSnapshot.AnchorIdentity anchorIdentity = snapshot.anchorIdentity();
        AgentReadinessSnapshot.LifecycleState lifecycleState = snapshot.lifecycleState();
        AgentReadinessSnapshot.DistributionIdentity distributionIdentity = snapshot.distributionIdentity();
        AgentReadinessSnapshot.ClassloaderTopology classloaderTopology = snapshot.classloaderTopology();

        return new TracingDeploymentDiagnostics(
                tracingEnabled,
                agentMarkerVisible,
                state,
                new TracingDeploymentDiagnostics.AgentReadiness(
                        true,

                        new TracingDeploymentDiagnostics.ContractIdentity(
                                contractIdentity.contractId(),
                                contractIdentity.schemaDigest()
                        ),

                        new TracingDeploymentDiagnostics.ReadinessAnchor(
                                anchorIdentity.runtimeEpoch(),
                                anchorIdentity.evidenceGeneration(),
                                anchorIdentity.batchId()
                        ),

                        new TracingDeploymentDiagnostics.LifecycleState(
                                lifecycleState.lifecycle(),
                                failureCode,
                                lifecycleState.capabilities(),
                                lifecycleState.missingRequired()
                        ),

                        new TracingDeploymentDiagnostics.DistributionIdentity(
                                distributionIdentity.agentDistributionSha256(),
                                distributionIdentity.upstreamAgentVersion(),
                                distributionIdentity.extensionVersion()
                        ),

                        new TracingDeploymentDiagnostics.ClassloaderTopology(
                                classloaderTopology.extensionDefiningLoader(),
                                classloaderTopology.sdkDefiningLoader()
                        )
                )
        );
    }

    private static TracingDeploymentDiagnostics terminalWithoutSnapshot(boolean tracingEnabled,
                                                                        AgentRuntimeState state,
                                                                        boolean agentMarkerVisible,
                                                                        boolean readinessEndpointPresent,
                                                                        String failureCode) {
        if (!readinessEndpointPresent) {
            return TracingDeploymentDiagnostics.withoutAgentData(
                    tracingEnabled,
                    agentMarkerVisible,
                    state,
                    failureCode
            );
        }

        TracingDeploymentDiagnostics.AgentReadiness unavailable = TracingDeploymentDiagnostics.AgentReadiness.unavailable(failureCode);
        return new TracingDeploymentDiagnostics(
                tracingEnabled,
                agentMarkerVisible,
                state,
                new TracingDeploymentDiagnostics.AgentReadiness(
                        true,
                        unavailable.contractIdentity(),
                        unavailable.readinessAnchor(),
                        unavailable.lifecycleState(),
                        unavailable.distributionIdentity(),
                        unavailable.classloaderTopology()
                )
        );
    }

    private static TracingDeploymentDiagnostics enforceContract(boolean tracingEnabled,
                                                                TracingDeploymentDiagnostics diagnostics,
                                                                Supplier<String> applicationVersion) {
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
}
