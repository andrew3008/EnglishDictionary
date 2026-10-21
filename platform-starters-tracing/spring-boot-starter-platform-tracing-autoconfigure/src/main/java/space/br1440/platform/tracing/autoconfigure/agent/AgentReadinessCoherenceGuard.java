package space.br1440.platform.tracing.autoconfigure.agent;

import java.lang.management.ManagementFactory;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import javax.management.MBeanServer;
import javax.management.ObjectName;

import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxWireContract;

public final class AgentReadinessCoherenceGuard {

    public static final String MIXED_GENERATION = AgentReadinessProbe.ProbeReason.MIXED_GENERATION.failureCode();
    public static final String CONTENT_DRIFT = "READINESS_ANCHOR_CONTENT_DRIFT";
    public static final String PINNED_ANCHOR_CHANGED = "READINESS_PINNED_ANCHOR_CHANGED";
    public static final String ANCHOR_NOT_PINNED = "READINESS_ANCHOR_NOT_PINNED";
    public static final String JMX_AGENT_INSTANCE_MISMATCH = "JMX_AGENT_INSTANCE_MISMATCH";

    private static final String READINESS_NOT_READY = "READINESS_NOT_READY";
    private static final ObjectName READINESS_NAME = JmxWireContract.EXTENSION_READINESS_OBJECT_NAME;

    private final AgentReadinessProbe probe;
    private final AtomicReference<AgentReadinessSnapshot> pinnedSnapshot = new AtomicReference<>();
    private final AtomicReference<String> terminalFailureCode = new AtomicReference<>();

    public AgentReadinessCoherenceGuard() {
        this(ManagementFactory.getPlatformMBeanServer());
    }

    public AgentReadinessCoherenceGuard(MBeanServer server) {
        this.probe = new AgentReadinessProbe(Objects.requireNonNull(server, "server"));
    }

    public <T, E extends Exception> T readCoherently(CheckedSupplier<T, E> trustedRead) throws E {
        Objects.requireNonNull(trustedRead, "trustedRead");

        requireNotTerminal();
        AgentReadinessSnapshot before = readReadySnapshot();
        requireMatchesPinned(before);
        requireNotTerminal();

        T value = trustedRead.get();

        requireNotTerminal();
        AgentReadinessSnapshot after = readReadySnapshot();
        requireSameGeneration(before, after);
        requireMatchesPinned(after);
        requireNotTerminal();
        return value;
    }

    void pin(AgentReadinessSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");

        requireNotTerminal();
        requireAgentReady(snapshot);
        pinnedSnapshot.compareAndSet(null, snapshot);
        requireMatchesPinned(snapshot);
        requireNotTerminal();
    }

    public String terminalFailureCode() {
        return terminalFailureCode.get();
    }

    boolean endpointRegistered() {
        return probe.endpointRegistered(READINESS_NAME);
    }

    AgentReadinessProbe.ProbeResult readSnapshot() {
        return probe.read(READINESS_NAME);
    }

    AgentReadinessProbe.ProbeResult readStableSnapshot() {
        AgentReadinessProbe.ProbeResult first = readSnapshot();
        if (!(first instanceof AgentReadinessProbe.ProbeResult.Present(AgentReadinessSnapshot firstSnapshot))) {
            return first;
        }

        AgentReadinessProbe.ProbeResult second = readSnapshot();
        if (!(second instanceof AgentReadinessProbe.ProbeResult.Present(AgentReadinessSnapshot secondSnapshot))) {
            return second;
        }

        return firstSnapshot.equals(secondSnapshot)
                ? second
                : new AgentReadinessProbe.ProbeResult.Rejected(AgentReadinessProbe.ProbeReason.MIXED_GENERATION);
    }

    public void requirePinnedAgentInstanceId(String observedAgentInstanceId) {
        String canonicalAgentInstanceId = AgentInstanceIdValidator.requireCanonical(
                observedAgentInstanceId,
                "agentInstanceId"
        );
        requireNotTerminal();

        AgentReadinessSnapshot pinned = requirePinnedSnapshot();
        if (!pinned.anchorIdentity().agentInstanceId().equals(canonicalAgentInstanceId)) {
            throw failure(terminalize(JMX_AGENT_INSTANCE_MISMATCH));
        }

        requireNotTerminal();
    }

    public void recordTrustFailure(String failureCode) {
        Objects.requireNonNull(failureCode, "failureCode");

        if (failureCode.isBlank()) {
            throw new IllegalArgumentException("JMX trust failure code must not be blank");
        }

        if (pinnedSnapshot.get() != null) {
            terminalize(failureCode);
        }
    }

    private AgentReadinessSnapshot readReadySnapshot() {
        AgentReadinessProbe.ProbeResult result = readSnapshot();
        if (!(result instanceof AgentReadinessProbe.ProbeResult.Present(AgentReadinessSnapshot snapshot))) {
            throw failure(AgentReadinessProbe.ProbeResult.reasonOf(result).failureCode());
        }

        requireAgentReady(snapshot);
        return snapshot;
    }

    private static void requireAgentReady(AgentReadinessSnapshot snapshot) {
        AgentReadinessClassifier.Decision decision = AgentReadinessClassifier.classify(snapshot);
        if (decision.state() != AgentRuntimeState.AGENT_READY) {
            throw failure(decision.reasonCode().isBlank() ? READINESS_NOT_READY : decision.reasonCode());
        }
    }

    private static void requireSameGeneration(AgentReadinessSnapshot before, AgentReadinessSnapshot after) {
        if (!before.anchorIdentity().equals(after.anchorIdentity())) {
            throw failure(MIXED_GENERATION);
        }

        if (!before.equals(after)) {
            throw failure(CONTENT_DRIFT);
        }
    }

    private void requireMatchesPinned(AgentReadinessSnapshot observed) {
        AgentReadinessSnapshot pinned = requirePinnedSnapshot();
        if (!pinned.anchorIdentity().equals(observed.anchorIdentity())) {
            throw failure(PINNED_ANCHOR_CHANGED);
        }

        if (!pinned.equals(observed)) {
            throw failure(CONTENT_DRIFT);
        }
    }

    private AgentReadinessSnapshot requirePinnedSnapshot() {
        AgentReadinessSnapshot pinned = pinnedSnapshot.get();
        if (pinned == null) {
            throw failure(ANCHOR_NOT_PINNED);
        }

        return pinned;
    }

    private void requireNotTerminal() {
        String failureCode = terminalFailureCode.get();
        if (failureCode != null) {
            throw failure(failureCode);
        }
    }

    private String terminalize(String failureCode) {
        return terminalFailureCode.updateAndGet(current -> current != null ? current : failureCode);
    }

    private static AgentReadinessCoherenceException failure(String failureCode) {
        return new AgentReadinessCoherenceException(failureCode);
    }

    @FunctionalInterface
    public interface CheckedSupplier<T, E extends Exception> {
        T get() throws E;
    }
}
