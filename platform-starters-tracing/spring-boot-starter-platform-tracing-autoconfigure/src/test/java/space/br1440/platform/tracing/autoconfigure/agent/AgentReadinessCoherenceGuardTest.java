package space.br1440.platform.tracing.autoconfigure.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxWireContract;

final class AgentReadinessCoherenceGuardTest {

    private static final String READINESS_ENDPOINT_DISAPPEARED = "READINESS_ENDPOINT_DISAPPEARED";

    @Test
    void emptyServerRejectsBeforeTrustedRead() {
        // given
        MBeanServer server = MBeanServerFactory.createMBeanServer();
        AgentReadinessCoherenceGuard guard = new AgentReadinessCoherenceGuard(server);
        AtomicInteger trustedReads = new AtomicInteger();

        // when
        // then
        assertThatThrownBy(() -> guard.readCoherently(trustedReads::incrementAndGet))
                .isInstanceOf(AgentReadinessCoherenceException.class)
                .satisfies(failure -> assertThat(
                        ((AgentReadinessCoherenceException) failure).failureCode())
                        .isEqualTo(READINESS_ENDPOINT_DISAPPEARED));
        assertThat(trustedReads).hasValue(0);
    }

    @Test
    void removedPinnedEndpointRejectsBeforeTrustedRead() throws Exception {
        // given
        MBeanServer server = MBeanServerFactory.createMBeanServer();
        AgentReadinessSnapshot ready = ReadinessTestFixtures.readySnapshot();
        ReadinessTestFixtures.register(server, () -> ReadinessTestFixtures.composite(ready));
        AgentReadinessCoherenceGuard guard = new AgentReadinessCoherenceGuard(server);
        guard.pin(ready);
        server.unregisterMBean(JmxWireContract.EXTENSION_READINESS_OBJECT_NAME);
        AtomicInteger trustedReads = new AtomicInteger();

        // when
        // then
        assertThatThrownBy(() -> guard.readCoherently(trustedReads::incrementAndGet))
                .isInstanceOf(AgentReadinessCoherenceException.class)
                .satisfies(failure -> assertThat(
                        ((AgentReadinessCoherenceException) failure).failureCode())
                        .isEqualTo(READINESS_ENDPOINT_DISAPPEARED));
        assertThat(trustedReads).hasValue(0);
    }

    @Test
    void endpointRemovedDuringTrustedReadRejectsCollectedValue() {
        // given
        MBeanServer server = MBeanServerFactory.createMBeanServer();
        AgentReadinessSnapshot ready = ReadinessTestFixtures.readySnapshot();
        ReadinessTestFixtures.register(server, () -> ReadinessTestFixtures.composite(ready));
        AgentReadinessCoherenceGuard guard = new AgentReadinessCoherenceGuard(server);
        guard.pin(ready);
        AtomicInteger trustedReads = new AtomicInteger();

        // when
        // then
        assertThatThrownBy(() -> guard.readCoherently(() -> {
            trustedReads.incrementAndGet();
            server.unregisterMBean(JmxWireContract.EXTENSION_READINESS_OBJECT_NAME);
            return "trusted";
        }))
                .isInstanceOf(AgentReadinessCoherenceException.class)
                .satisfies(failure -> assertThat(
                        ((AgentReadinessCoherenceException) failure).failureCode())
                        .isEqualTo(READINESS_ENDPOINT_DISAPPEARED));
        assertThat(trustedReads).hasValue(1);
    }

    @Test
    void contentDriftWithoutAnchorAdvanceIsRejected() {
        // given
        MBeanServer server = MBeanServerFactory.createMBeanServer();
        AgentReadinessSnapshot first = ReadinessTestFixtures.readySnapshot();
        AgentReadinessSnapshot second = withLoader(first, "agent-loader@2");
        AtomicInteger reads = new AtomicInteger();
        ReadinessTestFixtures.register(
                server,
                () -> ReadinessTestFixtures.composite(reads.getAndIncrement() == 0 ? first : second)
        );
        AgentReadinessCoherenceGuard guard = new AgentReadinessCoherenceGuard(server);
        guard.pin(first);

        // when
        // then
        assertThatThrownBy(() -> guard.readCoherently(() -> "untrusted"))
                .isInstanceOf(AgentReadinessCoherenceException.class)
                .satisfies(failure -> assertThat(
                        ((AgentReadinessCoherenceException) failure).failureCode())
                        .isEqualTo(AgentReadinessCoherenceGuard.CONTENT_DRIFT));
    }

    @Test
    void anchorAdvanceDuringTrustedReadIsMixedGenerationRatherThanContentDrift() {
        // given
        MBeanServer server = MBeanServerFactory.createMBeanServer();
        AgentReadinessSnapshot pinned = ReadinessTestFixtures.readySnapshot();
        AtomicReference<AgentReadinessSnapshot> current = new AtomicReference<>(pinned);
        ReadinessTestFixtures.register(server, () -> ReadinessTestFixtures.composite(current.get()));
        AgentReadinessCoherenceGuard guard = new AgentReadinessCoherenceGuard(server);
        guard.pin(pinned);

        // when
        // then
        assertThatThrownBy(() -> guard.readCoherently(() -> {
            current.set(withGeneration(pinned, 2L, "batch-2"));
            return "untrusted";
        }))
                .isInstanceOf(AgentReadinessCoherenceException.class)
                .satisfies(failure -> assertThat(
                        ((AgentReadinessCoherenceException) failure).failureCode())
                        .isEqualTo(AgentReadinessCoherenceGuard.MIXED_GENERATION));
    }

    @Test
    void trustFailureRecordedDuringTrustedReadInvalidatesItsResult() {
        // given
        MBeanServer server = MBeanServerFactory.createMBeanServer();
        AgentReadinessSnapshot ready = ReadinessTestFixtures.readySnapshot();
        ReadinessTestFixtures.register(server, () -> ReadinessTestFixtures.composite(ready));
        AgentReadinessCoherenceGuard guard = new AgentReadinessCoherenceGuard(server);
        guard.pin(ready);

        // when
        // then
        assertThatThrownBy(() -> guard.readCoherently(() -> {
            guard.recordTrustFailure("JMX_OBSERVATION_PROVIDER_FAILURE");
            return "untrusted";
        }))
                .isInstanceOf(AgentReadinessCoherenceException.class)
                .satisfies(failure -> assertThat(
                        ((AgentReadinessCoherenceException) failure).failureCode())
                        .isEqualTo("JMX_OBSERVATION_PROVIDER_FAILURE"));
        assertThat(guard.terminalFailureCode()).isEqualTo("JMX_OBSERVATION_PROVIDER_FAILURE");
    }

    @Test
    void unpinnedGuardRejectsBeforeTrustedRead() {
        // given
        MBeanServer server = MBeanServerFactory.createMBeanServer();
        AgentReadinessSnapshot ready = ReadinessTestFixtures.readySnapshot();
        ReadinessTestFixtures.register(server, () -> ReadinessTestFixtures.composite(ready));
        AgentReadinessCoherenceGuard guard = new AgentReadinessCoherenceGuard(server);
        AtomicInteger trustedReads = new AtomicInteger();

        // when
        // then
        assertThatThrownBy(() -> guard.readCoherently(trustedReads::incrementAndGet))
                .isInstanceOf(AgentReadinessCoherenceException.class)
                .satisfies(failure -> assertThat(
                        ((AgentReadinessCoherenceException) failure).failureCode())
                        .isEqualTo(AgentReadinessCoherenceGuard.ANCHOR_NOT_PINNED));
        assertThat(trustedReads).hasValue(0);
    }

    @Test
    void changedPinnedGenerationRejectsBeforeTrustedRead() {
        // given
        MBeanServer server = MBeanServerFactory.createMBeanServer();
        AgentReadinessSnapshot pinned = ReadinessTestFixtures.readySnapshot();
        AgentReadinessSnapshot current = withGeneration(pinned, 2L, "batch-2");
        ReadinessTestFixtures.register(server, () -> ReadinessTestFixtures.composite(current));
        AgentReadinessCoherenceGuard guard = new AgentReadinessCoherenceGuard(server);
        guard.pin(pinned);
        AtomicInteger trustedReads = new AtomicInteger();

        // when
        // then
        assertThatThrownBy(() -> guard.readCoherently(trustedReads::incrementAndGet))
                .isInstanceOf(AgentReadinessCoherenceException.class)
                .satisfies(failure -> assertThat(
                        ((AgentReadinessCoherenceException) failure).failureCode())
                        .isEqualTo(AgentReadinessCoherenceGuard.PINNED_ANCHOR_CHANGED));
        assertThat(trustedReads).hasValue(0);
    }

    @Test
    void changedPinnedContentRejectsBeforeTrustedRead() {
        // given
        MBeanServer server = MBeanServerFactory.createMBeanServer();
        AgentReadinessSnapshot pinned = ReadinessTestFixtures.readySnapshot();
        AgentReadinessSnapshot current = withLoader(pinned, "agent-loader@2");
        ReadinessTestFixtures.register(server, () -> ReadinessTestFixtures.composite(current));
        AgentReadinessCoherenceGuard guard = new AgentReadinessCoherenceGuard(server);
        guard.pin(pinned);
        AtomicInteger trustedReads = new AtomicInteger();

        // when
        // then
        assertThatThrownBy(() -> guard.readCoherently(trustedReads::incrementAndGet))
                .isInstanceOf(AgentReadinessCoherenceException.class)
                .satisfies(failure -> assertThat(
                        ((AgentReadinessCoherenceException) failure).failureCode())
                        .isEqualTo(AgentReadinessCoherenceGuard.CONTENT_DRIFT));
        assertThat(trustedReads).hasValue(0);
    }

    @Test
    void trustFailureBeforeReadyPinDoesNotTerminallyInvalidateStartup() {
        // given
        MBeanServer server = MBeanServerFactory.createMBeanServer();
        AgentReadinessSnapshot ready = ReadinessTestFixtures.readySnapshot();
        ReadinessTestFixtures.register(server, () -> ReadinessTestFixtures.composite(ready));
        AgentReadinessCoherenceGuard guard = new AgentReadinessCoherenceGuard(server);
        guard.recordTrustFailure("JMX_OBSERVATION_ENDPOINT_DISAPPEARED");

        // when
        guard.pin(ready);

        // then
        assertThat(guard.readCoherently(() -> "trusted")).isEqualTo("trusted");
    }

    @Test
    void firstTrustFailureAfterReadyPinIsStickyAndSkipsTrustedRead() {
        // given
        MBeanServer server = MBeanServerFactory.createMBeanServer();
        AgentReadinessSnapshot ready = ReadinessTestFixtures.readySnapshot();
        ReadinessTestFixtures.register(server, () -> ReadinessTestFixtures.composite(ready));
        AgentReadinessCoherenceGuard guard = new AgentReadinessCoherenceGuard(server);
        guard.pin(ready);
        AtomicInteger trustedReads = new AtomicInteger();
        guard.recordTrustFailure("JMX_OBSERVATION_ENDPOINT_DISAPPEARED");
        guard.recordTrustFailure("JMX_OBSERVATION_PROVIDER_FAILURE");

        // when
        // then
        assertThatThrownBy(() -> guard.readCoherently(trustedReads::incrementAndGet))
                .isInstanceOf(AgentReadinessCoherenceException.class)
                .satisfies(failure -> assertThat(
                        ((AgentReadinessCoherenceException) failure).failureCode())
                        .isEqualTo("JMX_OBSERVATION_ENDPOINT_DISAPPEARED"));
        assertThat(trustedReads).hasValue(0);
    }

    @Test
    void mismatchedAgentInstanceIdTerminallyInvalidatesPinnedTrust() {
        // given
        MBeanServer server = MBeanServerFactory.createMBeanServer();
        AgentReadinessSnapshot ready = ReadinessTestFixtures.readySnapshot();
        ReadinessTestFixtures.register(server, () -> ReadinessTestFixtures.composite(ready));
        AgentReadinessCoherenceGuard guard = new AgentReadinessCoherenceGuard(server);
        guard.pin(ready);
        AtomicInteger trustedReads = new AtomicInteger();

        // when
        // then
        assertThatThrownBy(() -> guard.requirePinnedAgentInstanceId(
                "28215585-4a76-40df-a50d-16452a6fa684"
        ))
                .isInstanceOf(AgentReadinessCoherenceException.class)
                .satisfies(failure -> assertThat(
                        ((AgentReadinessCoherenceException) failure).failureCode())
                        .isEqualTo(AgentReadinessCoherenceGuard.JMX_AGENT_INSTANCE_MISMATCH));

        assertThat(guard.terminalFailureCode())
                .isEqualTo(AgentReadinessCoherenceGuard.JMX_AGENT_INSTANCE_MISMATCH);
        assertThatThrownBy(() -> guard.readCoherently(trustedReads::incrementAndGet))
                .isInstanceOf(AgentReadinessCoherenceException.class)
                .satisfies(failure -> assertThat(
                        ((AgentReadinessCoherenceException) failure).failureCode())
                        .isEqualTo(AgentReadinessCoherenceGuard.JMX_AGENT_INSTANCE_MISMATCH));
        assertThat(trustedReads).hasValue(0);
    }

    @Test
    void inconsistentFailedCodePairUsesClassifierReasonBeforeTrustedRead() {
        // given
        MBeanServer server = MBeanServerFactory.createMBeanServer();
        AgentReadinessSnapshot pinned = ReadinessTestFixtures.readySnapshot();
        AgentReadinessSnapshot inconsistent = ReadinessTestFixtures.failedSnapshot(
                "PRIMARY_FAILURE",
                "UNRELATED_EFFECTIVE_FAILURE"
        );
        ReadinessTestFixtures.register(server, () -> ReadinessTestFixtures.composite(inconsistent));
        AgentReadinessCoherenceGuard guard = new AgentReadinessCoherenceGuard(server);
        guard.pin(pinned);
        AtomicInteger trustedReads = new AtomicInteger();

        // when
        // then
        assertThatThrownBy(() -> guard.readCoherently(trustedReads::incrementAndGet))
                .isInstanceOf(AgentReadinessCoherenceException.class)
                .satisfies(failure -> assertThat(
                        ((AgentReadinessCoherenceException) failure).failureCode())
                        .isEqualTo("EXTENSION_FAILED_CODE_PAIR_INCONSISTENT"));
        assertThat(trustedReads).hasValue(0);
    }

    @Test
    void readyFailureCodeUsesClassifierReasonBeforeTrustedRead() {
        // given
        MBeanServer server = MBeanServerFactory.createMBeanServer();
        AgentReadinessSnapshot pinned = ReadinessTestFixtures.readySnapshot();
        AgentReadinessSnapshot.LifecycleState lifecycle = pinned.lifecycleState();
        AgentReadinessSnapshot inconsistent = ReadinessTestFixtures.toBuilder(pinned)
                .lifecycleState(new AgentReadinessSnapshot.LifecycleState(
                        "READY",
                        "ILLEGAL_READY_FAILURE",
                        "",
                        lifecycle.capabilities(),
                        lifecycle.missingRequired()))
                .build();
        ReadinessTestFixtures.register(server, () -> ReadinessTestFixtures.composite(inconsistent));
        AgentReadinessCoherenceGuard guard = new AgentReadinessCoherenceGuard(server);
        guard.pin(pinned);
        AtomicInteger trustedReads = new AtomicInteger();

        // when
        // then
        assertThatThrownBy(() -> guard.readCoherently(trustedReads::incrementAndGet))
                .isInstanceOf(AgentReadinessCoherenceException.class)
                .satisfies(failure -> assertThat(
                        ((AgentReadinessCoherenceException) failure).failureCode())
                        .isEqualTo("EXTENSION_READY_WITH_FAILURE_CODE"));
        assertThat(trustedReads).hasValue(0);
    }

    @SuppressWarnings("SameParameterValue")
    private static AgentReadinessSnapshot withLoader(AgentReadinessSnapshot source,
                                                     String extensionDefiningLoader) {
        AgentReadinessSnapshot.ClassloaderTopology classloaderTopology = source.classloaderTopology();
        return ReadinessTestFixtures.toBuilder(source)
                .classloaderTopology(new AgentReadinessSnapshot.ClassloaderTopology(
                        extensionDefiningLoader, classloaderTopology.sdkDefiningLoader())
                )
                .build();
    }

    @SuppressWarnings("SameParameterValue")
    private static AgentReadinessSnapshot withGeneration(AgentReadinessSnapshot source,
                                                         long evidenceGeneration,
                                                         String batchId) {
        AgentReadinessSnapshot.AnchorIdentity anchorIdentity = source.anchorIdentity();
        return ReadinessTestFixtures.toBuilder(source)
                .anchorIdentity(new AgentReadinessSnapshot.AnchorIdentity(
                        anchorIdentity.agentInstanceId(), evidenceGeneration, batchId)
                )
                .build();
    }
}
