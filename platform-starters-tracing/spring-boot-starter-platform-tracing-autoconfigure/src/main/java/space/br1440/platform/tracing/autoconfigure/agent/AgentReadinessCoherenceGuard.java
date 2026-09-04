package space.br1440.platform.tracing.autoconfigure.agent;

import java.lang.management.ManagementFactory;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import javax.management.MBeanServer;
import javax.management.ObjectName;

import space.br1440.platform.common.util.StringUtils;
import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxWireContract;

public final class AgentReadinessCoherenceGuard {

    public static final String MIXED_GENERATION = AgentReadinessProbe.ProbeReason.MIXED_GENERATION.failureCode();
    public static final String CONTENT_DRIFT = "READINESS_ANCHOR_CONTENT_DRIFT";
    public static final String PINNED_EPOCH_CHANGED = "READINESS_PINNED_EPOCH_CHANGED";
    public static final String ANCHOR_NOT_PINNED = "READINESS_ANCHOR_NOT_PINNED";

    private final AgentReadinessProbe probe;
    private final ObjectName readinessName;
    private final AtomicReference<AgentReadinessSnapshot> pinnedSnapshot = new AtomicReference<>();
    private final AtomicReference<String> terminalFailureCode = new AtomicReference<>();

    public AgentReadinessCoherenceGuard() {
        this(ManagementFactory.getPlatformMBeanServer());
    }

    public AgentReadinessCoherenceGuard(MBeanServer server) {
        this.probe = new AgentReadinessProbe(Objects.requireNonNull(server, "server"));
        this.readinessName = JmxWireContract.EXTENSION_READINESS_OBJECT_NAME;
    }

    public <T, E extends Exception> T readCoherently(CheckedSupplier<T, E> trustedRead) throws E {
        Objects.requireNonNull(trustedRead, "trustedRead");
        requireNotTerminal();

        AgentReadinessSnapshot before = requireReadySnapshot(probe.read(readinessName));
        requirePinned(before);
        requireNotTerminal();

        T value = trustedRead.get();

        requireNotTerminal();
        AgentReadinessSnapshot after = requireReadySnapshot(probe.read(readinessName));
        if (!before.anchorIdentity().equals(after.anchorIdentity())) {
            throw failure(MIXED_GENERATION);
        }

        if (!before.equals(after)) {
            throw failure(CONTENT_DRIFT);
        }

        requirePinned(after);
        requireNotTerminal();
        return value;
    }

    void pin(AgentReadinessSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");

        requireNotTerminal();
        String extensionVersion = snapshot.distributionIdentity().extensionVersion();
        AgentReadinessClassifier.Decision decision = AgentReadinessClassifier.decide(snapshot, extensionVersion);
        if (decision.state() != AgentRuntimeState.AGENT_READY) {
            throw failure(firstNonBlank(snapshot.lifecycleState().failureCode(), decision.reasonCode()));
        }

        pinnedSnapshot.compareAndSet(null, snapshot);
        requirePinned(snapshot);
        requireNotTerminal();
    }

    public String terminalFailureCode() {
        return terminalFailureCode.get();
    }

    boolean endpointRegistered() {
        return probe.endpointRegistered(readinessName);
    }

    AgentReadinessProbe.ProbeResult readSnapshot() {
        return probe.read(readinessName);
    }

    private AgentReadinessSnapshot requireReadySnapshot(AgentReadinessProbe.ProbeResult result) {
        if (!(result instanceof AgentReadinessProbe.ProbeResult.Present(AgentReadinessSnapshot snapshot))) {
            throw failure(AgentReadinessProbe.ProbeResult.reasonOf(result).failureCode());
        }

        String extensionVersion = snapshot.distributionIdentity().extensionVersion();
        AgentReadinessClassifier.Decision decision = AgentReadinessClassifier.decide(snapshot, extensionVersion);
        if (decision.state() != AgentRuntimeState.AGENT_READY) {
            throw failure(firstNonBlank(snapshot.lifecycleState().failureCode(), decision.reasonCode()));
        }

        return snapshot;
    }

    private void requirePinned(AgentReadinessSnapshot observed) {
        AgentReadinessSnapshot pinned = pinnedSnapshot.get();
        if (pinned == null) {
            throw failure(ANCHOR_NOT_PINNED);
        }

        if (!pinned.anchorIdentity().equals(observed.anchorIdentity())) {
            throw failure(PINNED_EPOCH_CHANGED);
        }

        if (!pinned.equals(observed)) {
            throw failure(CONTENT_DRIFT);
        }
    }

    public void recordTrustFailure(String failureCode) {
        Objects.requireNonNull(failureCode, "failureCode");

        if (failureCode.isBlank()) {
            throw new IllegalArgumentException("JMX trust failure code must not be blank");
        }

        if (pinnedSnapshot.get() != null) {
            terminalFailureCode.compareAndSet(null, failureCode);
        }
    }

    private void requireNotTerminal() {
        String failureCode = terminalFailureCode.get();
        if (failureCode != null) {
            throw failure(failureCode);
        }
    }

    private static String firstNonBlank(String first, String second) {
        if (StringUtils.hasText(first)) {
            return first;
        }

        return StringUtils.hasText(second) ? second : "READINESS_NOT_READY";
    }

    private static AgentReadinessCoherenceException failure(String failureCode) {
        return new AgentReadinessCoherenceException(failureCode);
    }

    @FunctionalInterface
    public interface CheckedSupplier<T, E extends Exception> {
        T get() throws E;
    }
}
