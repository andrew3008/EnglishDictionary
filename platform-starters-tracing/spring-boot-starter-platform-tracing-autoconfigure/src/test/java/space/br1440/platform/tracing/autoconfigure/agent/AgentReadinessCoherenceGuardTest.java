package space.br1440.platform.tracing.autoconfigure.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicInteger;
import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;

import org.junit.jupiter.api.Test;

final class AgentReadinessCoherenceGuardTest {

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
                        .isEqualTo(AgentReadinessCoherenceGuard.PINNED_EPOCH_CHANGED));
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
                        anchorIdentity.runtimeEpoch(), evidenceGeneration, batchId)
                )
                .build();
    }
}
