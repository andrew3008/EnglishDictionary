package space.br1440.platform.tracing.autoconfigure.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.management.JMException;
import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxWireContract;

class AgentStartupVerifierTest {

    @Test
    void failsClosedWhenAgentIsAbsent() {
        // given
        AgentStartupVerifier verifier = verifier(MBeanServerFactory.createMBeanServer());

        // when
        // then
        assertThatIllegalStateException()
                .isThrownBy(() -> verifier.verify(true, false, false, false))
                .withMessageContaining("runtimeState=AGENT_MISSING")
                .withMessageContaining("reason=CONTROLLED_AGENT_MISSING");
    }

    @Test
    void distinguishesAgentWithoutReadinessEndpoint() {
        // given
        AtomicLong clock = new AtomicLong();
        AtomicInteger sleeps = new AtomicInteger();
        AgentStartupVerifier verifier = new AgentStartupVerifier(
                new AgentReadinessCoherenceGuard(MBeanServerFactory.createMBeanServer()),
                Duration.ofMillis(50),
                clock::get,
                nanos -> {
                    sleeps.incrementAndGet();
                    clock.addAndGet(nanos);
                },
                () -> ReadinessTestFixtures.APPLICATION_VERSION);

        // when
        // then
        assertThatIllegalStateException()
                .isThrownBy(() -> verifier.verify(true, true, false, true))
                .withMessageContaining("runtimeState=EXTENSION_MISSING")
                .withMessageContaining("reason=PLATFORM_EXTENSION_MISSING");
        assertThat(clock).hasValue(Duration.ofMillis(50).toNanos());
        assertThat(sleeps).hasValue(2);
    }

    @Test
    void reportsExactDualSdkCollisionForApplicationOwnedRuntime() {
        // given
        AgentStartupVerifier verifier = verifier(MBeanServerFactory.createMBeanServer());

        // when
        // then
        assertThatIllegalStateException()
                .isThrownBy(() -> verifier.verify(true, true, true, true))
                .withMessageContaining("runtimeState=DUAL_SDK_DETECTED")
                .withMessageContaining("reason=DUAL_SDK_COLLISION");
    }

    @Test
    void pollsUntilReadinessEndpointAppearsAfterAgentMarker() {
        // given
        MBeanServer server = MBeanServerFactory.createMBeanServer();
        AtomicLong clock = new AtomicLong();
        AtomicInteger sleeps = new AtomicInteger();
        AgentReadinessSnapshot ready = ReadinessTestFixtures.readySnapshot();
        AgentStartupVerifier verifier = new AgentStartupVerifier(
                new AgentReadinessCoherenceGuard(server),
                Duration.ofMillis(50),
                clock::get,
                nanos -> {
                    sleeps.incrementAndGet();
                    clock.addAndGet(nanos);
                    ReadinessTestFixtures.register(server, () -> ReadinessTestFixtures.composite(ready));
                },
                () -> ReadinessTestFixtures.APPLICATION_VERSION);

        // when
        TracingDeploymentDiagnostics diagnostics = verifier.verify(true, true, false, true);

        // then
        assertThat(diagnostics.runtimeState()).isEqualTo(AgentRuntimeState.AGENT_READY);
        assertThat(sleeps).hasValue(1);
        assertThat(clock).hasValue(Duration.ofMillis(25).toNanos());
    }

    @Test
    void acceptsStableDoubleCollectedReadySnapshot() {
        // given
        MBeanServer server = MBeanServerFactory.createMBeanServer();
        AgentReadinessSnapshot ready = ReadinessTestFixtures.readySnapshot();
        ReadinessTestFixtures.register(server, () -> ReadinessTestFixtures.composite(ready));
        AgentStartupVerifier verifier = verifier(server);

        // when
        TracingDeploymentDiagnostics diagnostics = verifier.verify(true, true, false, true);

        // then
        assertThat(diagnostics.runtimeState()).isEqualTo(AgentRuntimeState.AGENT_READY);
        assertThat(diagnostics.agentReadiness()).isInstanceOf(TracingDeploymentDiagnostics.Observed.class);
        TracingDeploymentDiagnostics.Observed observed =
                (TracingDeploymentDiagnostics.Observed) diagnostics.agentReadiness();
        assertThat(observed.failureCode()).isEmpty();
        assertThat(observed.snapshot().contractIdentity().contractId())
                .isEqualTo(ready.contractIdentity().contractId());
        assertThat(observed.snapshot().anchorIdentity().agentInstanceId())
                .isEqualTo(ready.anchorIdentity().agentInstanceId());
        assertThat(observed.snapshot().anchorIdentity().evidenceGeneration())
                .isEqualTo(ready.anchorIdentity().evidenceGeneration());
        assertThat(observed.snapshot().anchorIdentity().batchId())
                .isEqualTo(ready.anchorIdentity().batchId());
        assertThat(observed.snapshot().distributionIdentity().agentDistributionSha256())
                .isEqualTo(ready.distributionIdentity().agentDistributionSha256());
        assertThat(observed.snapshot().classloaderTopology().extensionDefiningLoader())
                .isEqualTo(ready.classloaderTopology().extensionDefiningLoader());
        assertThat(observed.snapshot().classloaderTopology().sdkDefiningLoader())
                .isEqualTo(ready.classloaderTopology().sdkDefiningLoader());
    }

    @Test
    void pollsStableInitializingSnapshotUntilReady() {
        // given
        MBeanServer server = MBeanServerFactory.createMBeanServer();
        AtomicReference<AgentReadinessSnapshot> current =
                new AtomicReference<>(ReadinessTestFixtures.initializingSnapshot());
        ReadinessTestFixtures.register(server, () -> ReadinessTestFixtures.composite(current.get()));
        AtomicLong clock = new AtomicLong();
        AtomicInteger sleeps = new AtomicInteger();
        AgentStartupVerifier verifier = new AgentStartupVerifier(
                new AgentReadinessCoherenceGuard(server),
                Duration.ofSeconds(2),
                clock::get,
                nanos -> {
                    sleeps.incrementAndGet();
                    clock.addAndGet(nanos);
                    current.set(ReadinessTestFixtures.readySnapshot());
                },
                () -> ReadinessTestFixtures.APPLICATION_VERSION);

        // when
        TracingDeploymentDiagnostics diagnostics = verifier.verify(true, true, false, true);

        // then
        assertThat(diagnostics.runtimeState()).isEqualTo(AgentRuntimeState.AGENT_READY);
        assertThat(sleeps.get()).isEqualTo(1);
    }

    @Test
    void retriesInitializingToReadyTransitionBetweenDoubleCollectReads() {
        // given
        MBeanServer server = MBeanServerFactory.createMBeanServer();
        AgentReadinessSnapshot initializing = ReadinessTestFixtures.initializingSnapshot();
        AgentReadinessSnapshot ready = ReadinessTestFixtures.readySnapshot();
        AtomicInteger reads = new AtomicInteger();
        ReadinessTestFixtures.register(
                server,
                () -> ReadinessTestFixtures.composite(reads.getAndIncrement() == 0 ? initializing : ready));
        AtomicLong clock = new AtomicLong();
        AtomicInteger sleeps = new AtomicInteger();
        AgentStartupVerifier verifier = new AgentStartupVerifier(
                new AgentReadinessCoherenceGuard(server),
                Duration.ofMillis(50),
                clock::get,
                nanos -> {
                    sleeps.incrementAndGet();
                    clock.addAndGet(nanos);
                },
                () -> ReadinessTestFixtures.APPLICATION_VERSION);

        // when
        TracingDeploymentDiagnostics diagnostics = verifier.verify(true, true, false, true);

        // then
        assertThat(diagnostics.runtimeState()).isEqualTo(AgentRuntimeState.AGENT_READY);
        assertThat(reads).hasValue(4);
        assertThat(sleeps).hasValue(1);
    }

    @Test
    void failsClosedWhenInitializingSnapshotExceedsBoundedStartupDeadline() {
        // given
        MBeanServer server = MBeanServerFactory.createMBeanServer();
        AgentReadinessSnapshot initializing = ReadinessTestFixtures.initializingSnapshot();
        ReadinessTestFixtures.register(server, () -> ReadinessTestFixtures.composite(initializing));
        AtomicLong clock = new AtomicLong();
        AtomicInteger sleeps = new AtomicInteger();
        AgentStartupVerifier verifier = new AgentStartupVerifier(
                new AgentReadinessCoherenceGuard(server),
                Duration.ofMillis(50),
                clock::get,
                nanos -> {
                    sleeps.incrementAndGet();
                    clock.addAndGet(nanos);
                },
                () -> ReadinessTestFixtures.APPLICATION_VERSION);

        // when
        // then
        assertThatIllegalStateException()
                .isThrownBy(() -> verifier.verify(true, true, false, true))
                .withMessageContaining("runtimeState=EXTENSION_INITIALIZING")
                .withMessageContaining("reason=EXTENSION_READINESS_TIMEOUT")
                .withMessageContaining("OpenTelemetry SDK autoconfiguration may have been aborted after platform preparation");
        assertThat(clock.get()).isEqualTo(Duration.ofMillis(50).toNanos());
        assertThat(sleeps.get()).isEqualTo(2);
    }

    @Test
    void readyStartupPinsAnchorForSubsequentTrustedReads() {
        // given
        MBeanServer server = MBeanServerFactory.createMBeanServer();
        AtomicReference<AgentReadinessSnapshot> current =
                new AtomicReference<>(ReadinessTestFixtures.readySnapshot());
        ReadinessTestFixtures.register(server, () -> ReadinessTestFixtures.composite(current.get()));
        AgentReadinessCoherenceGuard coherenceGuard = new AgentReadinessCoherenceGuard(server);
        AgentStartupVerifier verifier = new AgentStartupVerifier(
                coherenceGuard,
                Duration.ofSeconds(2),
                System::nanoTime,
                nanos -> {
                },
                () -> ReadinessTestFixtures.APPLICATION_VERSION);
        verifier.verify(true, true, false, true);
        current.set(withGeneration(ReadinessTestFixtures.readySnapshot(), 2L, "batch-2"));

        // when
        // then
        assertThatThrownBy(() -> coherenceGuard.readCoherently(() -> "untrusted"))
                .isInstanceOf(AgentReadinessCoherenceException.class)
                .hasMessageContaining(AgentReadinessCoherenceGuard.PINNED_ANCHOR_CHANGED);
    }

    @Test
    void versionMismatchNeverPinsReadinessAnchor() {
        // given
        MBeanServer server = MBeanServerFactory.createMBeanServer();
        AgentReadinessSnapshot ready = ReadinessTestFixtures.readySnapshot();
        ReadinessTestFixtures.register(server, () -> ReadinessTestFixtures.composite(ready));
        AgentReadinessCoherenceGuard coherenceGuard = new AgentReadinessCoherenceGuard(server);
        AgentStartupVerifier verifier = new AgentStartupVerifier(
                coherenceGuard,
                Duration.ofSeconds(2),
                System::nanoTime,
                nanos -> {
                },
                () -> "9.9.9");

        // when
        // then
        assertThatIllegalStateException()
                .isThrownBy(() -> verifier.verify(true, true, false, true))
                .withMessageContaining("reason=EXTENSION_VERSION_MISMATCH");
        assertThatThrownBy(() -> coherenceGuard.readCoherently(() -> "must-not-run"))
                .isInstanceOf(AgentReadinessCoherenceException.class)
                .satisfies(failure -> assertThat(
                        ((AgentReadinessCoherenceException) failure).failureCode())
                        .isEqualTo(AgentReadinessCoherenceGuard.ANCHOR_NOT_PINNED));
    }

    @Test
    void rejectsFailedSnapshot() {
        // given
        MBeanServer server = MBeanServerFactory.createMBeanServer();
        AgentReadinessSnapshot failed = ReadinessTestFixtures.failedSnapshot("SPI_DISCOVERY_INCOMPLETE");
        ReadinessTestFixtures.register(server, () -> ReadinessTestFixtures.composite(failed));
        AgentStartupVerifier verifier = verifier(server);

        // when
        // then
        assertThatIllegalStateException()
                .isThrownBy(() -> verifier.verify(true, true, false, true))
                .withMessageContaining("runtimeState=EXTENSION_FAILED")
                .withMessageContaining("reason=SPI_DISCOVERY_INCOMPLETE");
    }

    @Test
    void retainsTerminalFailureAfterProducerAttemptsToReturnReady() {
        // given
        MBeanServer server = MBeanServerFactory.createMBeanServer();
        AtomicReference<AgentReadinessSnapshot> current =
                new AtomicReference<>(ReadinessTestFixtures.failedSnapshot("DISTRIBUTION_MISMATCH"));
        ReadinessTestFixtures.register(server, () -> ReadinessTestFixtures.composite(current.get()));
        AgentStartupVerifier verifier = verifier(server);
        assertThatIllegalStateException()
                .isThrownBy(() -> verifier.verify(true, true, false, true))
                .withMessageContaining("reason=DISTRIBUTION_MISMATCH");
        current.set(ReadinessTestFixtures.readySnapshot());

        // when
        // then
        assertThatIllegalStateException()
                .isThrownBy(() -> verifier.verify(true, true, false, true))
                .withMessageContaining("runtimeState=EXTENSION_FAILED")
                .withMessageContaining("reason=DISTRIBUTION_MISMATCH");
    }

    @Test
    void failsClosedWhenMixedGenerationPersistsUntilStartupDeadline() {
        // given
        MBeanServer server = MBeanServerFactory.createMBeanServer();
        AgentReadinessSnapshot first = ReadinessTestFixtures.readySnapshot();
        AgentReadinessSnapshot second = withGeneration(first, 2L, "batch-2");
        AtomicInteger reads = new AtomicInteger();
        AtomicLong clock = new AtomicLong();
        AtomicInteger sleeps = new AtomicInteger();
        ReadinessTestFixtures.register(
                server,
                () -> ReadinessTestFixtures.composite(reads.getAndIncrement() % 2 == 0 ? first : second));
        AgentStartupVerifier verifier = new AgentStartupVerifier(
                new AgentReadinessCoherenceGuard(server),
                Duration.ofMillis(50),
                clock::get,
                nanos -> {
                    sleeps.incrementAndGet();
                    clock.addAndGet(nanos);
                },
                () -> ReadinessTestFixtures.APPLICATION_VERSION);

        // when
        // then
        assertThatIllegalStateException()
                .isThrownBy(() -> verifier.verify(true, true, false, true))
                .withMessageContaining("runtimeState=EXTENSION_INCOMPATIBLE")
                .withMessageContaining("reason=READINESS_MIXED_GENERATION");
        assertThat(clock).hasValue(Duration.ofMillis(50).toNanos());
        assertThat(sleeps).hasValue(2);
        assertThat(reads).hasValue(6);
    }

    @Test
    void endpointDisappearingAfterSuccessfulReadIsIncompatibleRatherThanMissing() {
        // given
        MBeanServer server = MBeanServerFactory.createMBeanServer();
        AgentReadinessSnapshot initializing = ReadinessTestFixtures.initializingSnapshot();
        ReadinessTestFixtures.register(server, () -> ReadinessTestFixtures.composite(initializing));
        AgentStartupVerifier verifier = new AgentStartupVerifier(
                new AgentReadinessCoherenceGuard(FakeMBeanServers.alwaysReportRegistered(server)),
                Duration.ofSeconds(2),
                System::nanoTime,
                nanos -> {
                    try {
                        server.unregisterMBean(JmxWireContract.EXTENSION_READINESS_OBJECT_NAME);
                    } catch (JMException unexpected) {
                        throw new AssertionError(unexpected);
                    }
                },
                () -> ReadinessTestFixtures.APPLICATION_VERSION);

        // when
        // then
        assertThatIllegalStateException()
                .isThrownBy(() -> verifier.verify(true, true, false, true))
                .withMessageContaining("runtimeState=EXTENSION_INCOMPATIBLE")
                .withMessageContaining("reason=READINESS_ENDPOINT_DISAPPEARED");
    }

    @Test
    void rejectsSchemaMismatchBeforeReadyDecision() {
        // given
        MBeanServer server = MBeanServerFactory.createMBeanServer();
        AgentReadinessSnapshot mismatched = withSchemaDigest(
                ReadinessTestFixtures.readySnapshot(),
                "0".repeat(64));
        ReadinessTestFixtures.register(server, () -> ReadinessTestFixtures.composite(mismatched));
        AgentStartupVerifier verifier = verifier(server);

        // when
        // then
        assertThatIllegalStateException()
                .isThrownBy(() -> verifier.verify(true, true, false, true))
                .withMessageContaining("reason=READINESS_SCHEMA_DIGEST_MISMATCH");
    }

    @Test
    void rejectsMetadataAccessDenied() {
        // given
        MBeanServer empty = MBeanServerFactory.createMBeanServer();
        MBeanServer denying = FakeMBeanServers.throwingOnGetMBeanInfo(
                empty,
                new SecurityException("denied"));
        AgentStartupVerifier verifier = verifier(FakeMBeanServers.alwaysReportRegistered(denying));

        // when
        // then
        assertThatIllegalStateException()
                .isThrownBy(() -> verifier.verify(true, true, false, true))
                .withMessageContaining("reason=READINESS_ACCESS_DENIED");
    }

    @Test
    void returnsDisabledWithoutReadingAbsentEndpoint() {
        // given
        AtomicLong clock = new AtomicLong();
        AtomicInteger sleeps = new AtomicInteger();
        AgentStartupVerifier verifier = new AgentStartupVerifier(
                new AgentReadinessCoherenceGuard(MBeanServerFactory.createMBeanServer()),
                Duration.ofSeconds(2),
                clock::get,
                nanos -> {
                    sleeps.incrementAndGet();
                    clock.addAndGet(nanos);
                },
                () -> ReadinessTestFixtures.APPLICATION_VERSION);

        // when
        TracingDeploymentDiagnostics diagnostics = verifier.verify(false, false, false, false);

        // then
        assertThat(diagnostics.runtimeState()).isEqualTo(AgentRuntimeState.DISABLED);
        assertThat(diagnostics.agentReadiness())
                .isEqualTo(new TracingDeploymentDiagnostics.EndpointAbsent(""));
        assertThat(clock.get()).isZero();
        assertThat(sleeps.get()).isZero();
    }

    @Test
    void disabledOutcomeNeverDereferencesTheStarterModuleVersionSource() {
        // given
        AgentReadinessCoherenceGuard coherenceGuard = new AgentReadinessCoherenceGuard(MBeanServerFactory.createMBeanServer());
        AgentStartupVerifier verifier = new AgentStartupVerifier(
                coherenceGuard,
                () -> {
                    throw new AssertionError("applicationVersion must not be read on the DISABLED success path");
                });

        // when
        TracingDeploymentDiagnostics diagnostics = verifier.verify(false, false, false, false);

        // then
        assertThat(diagnostics.runtimeState()).isEqualTo(AgentRuntimeState.DISABLED);
    }

    @Test
    void missingAgentOutcomeNeverDereferencesTheStarterModuleVersionSource() {
        // given
        AgentStartupVerifier verifier = new AgentStartupVerifier(
                new AgentReadinessCoherenceGuard(MBeanServerFactory.createMBeanServer()),
                () -> {
                    throw new AssertionError("applicationVersion must not be read when the agent is missing");
                });

        // when
        // then
        assertThatIllegalStateException()
                .isThrownBy(() -> verifier.verify(true, false, false, false))
                .withMessageContaining("runtimeState=AGENT_MISSING")
                .withMessageContaining("reason=CONTROLLED_AGENT_MISSING");
    }

    @Test
    void modeForbiddenTakesPriorityOverCompatibleReadyAgent() {
        // given
        MBeanServer server = MBeanServerFactory.createMBeanServer();
        AgentReadinessSnapshot ready = ReadinessTestFixtures.readySnapshot();
        ReadinessTestFixtures.register(server, () -> ReadinessTestFixtures.composite(ready));
        AgentStartupVerifier verifier = verifier(server);

        // when
        // then
        assertThatIllegalStateException()
                .isThrownBy(() -> verifier.verify(false, true, false, false))
                .withMessageContaining("runtimeState=AGENT_READY")
                .withMessageContaining("reason=CONTROLLED_AGENT_FORBIDDEN");
    }

    @Test
    void modeForbiddenTakesPriorityOverVersionMismatch() {
        // given
        MBeanServer server = MBeanServerFactory.createMBeanServer();
        AgentReadinessSnapshot ready = ReadinessTestFixtures.readySnapshot();
        AgentReadinessSnapshot incompatible = ReadinessTestFixtures.toBuilder(ready)
                .distributionIdentity(new AgentReadinessSnapshot.DistributionIdentity(
                        ready.distributionIdentity().agentDistributionSha256(),
                        ready.distributionIdentity().upstreamAgentVersion(),
                        "9.9.9"))
                .build();
        ReadinessTestFixtures.register(server, () -> ReadinessTestFixtures.composite(incompatible));
        AgentStartupVerifier verifier = verifier(server);

        // when
        // then
        assertThatIllegalStateException()
                .isThrownBy(() -> verifier.verify(false, true, false, false))
                .withMessageContaining("reason=CONTROLLED_AGENT_FORBIDDEN")
                .satisfies(failure -> assertThat(failure.getMessage())
                        .doesNotContain("EXTENSION_VERSION_MISMATCH"));
    }

    @Test
    void enabledTrueDoesNotForgiveVersionMismatch() {
        // given
        MBeanServer server = MBeanServerFactory.createMBeanServer();
        AgentReadinessSnapshot ready = ReadinessTestFixtures.readySnapshot();
        AgentReadinessSnapshot incompatible = ReadinessTestFixtures.toBuilder(ready)
                .distributionIdentity(new AgentReadinessSnapshot.DistributionIdentity(
                        ready.distributionIdentity().agentDistributionSha256(),
                        ready.distributionIdentity().upstreamAgentVersion(),
                        "9.9.9"))
                .build();
        ReadinessTestFixtures.register(server, () -> ReadinessTestFixtures.composite(incompatible));
        AgentStartupVerifier verifier = verifier(server);

        // when
        // then
        assertThatIllegalStateException()
                .isThrownBy(() -> verifier.verify(true, true, false, true))
                .withMessageContaining("reason=EXTENSION_VERSION_MISMATCH");
    }

    @Test
    void modeForbiddenTakesPriorityOverFailedAgentWithFailureCode() {
        // given
        MBeanServer server = MBeanServerFactory.createMBeanServer();
        AgentReadinessSnapshot failed = ReadinessTestFixtures.failedSnapshot("SPI_DISCOVERY_INCOMPLETE");
        ReadinessTestFixtures.register(server, () -> ReadinessTestFixtures.composite(failed));
        AgentStartupVerifier verifier = verifier(server);

        // when
        // then
        assertThatIllegalStateException()
                .isThrownBy(() -> verifier.verify(false, true, false, false))
                .withMessageContaining("runtimeState=EXTENSION_FAILED")
                .withMessageContaining("reason=CONTROLLED_AGENT_FORBIDDEN")
                .satisfies(failure -> assertThat(failure.getMessage())
                        .doesNotContain("SPI_DISCOVERY_INCOMPLETE"));
    }

    @Test
    void enabledTrueDoesNotForgiveFailedAgentFailureCode() {
        // given
        MBeanServer server = MBeanServerFactory.createMBeanServer();
        AgentReadinessSnapshot failed = ReadinessTestFixtures.failedSnapshot("SPI_DISCOVERY_INCOMPLETE");
        ReadinessTestFixtures.register(server, () -> ReadinessTestFixtures.composite(failed));
        AgentStartupVerifier verifier = verifier(server);

        // when
        // then
        assertThatIllegalStateException()
                .isThrownBy(() -> verifier.verify(true, true, false, true))
                .withMessageContaining("runtimeState=EXTENSION_FAILED")
                .withMessageContaining("reason=SPI_DISCOVERY_INCOMPLETE");
    }

    @Test
    void failedCodePairInconsistencyReportsClassifierReason() {
        // given
        MBeanServer server = MBeanServerFactory.createMBeanServer();
        AgentReadinessSnapshot failed = ReadinessTestFixtures.failedSnapshot(
                "PRIMARY_FAILURE",
                "UNRELATED_EFFECTIVE_FAILURE");
        ReadinessTestFixtures.register(server, () -> ReadinessTestFixtures.composite(failed));
        AgentStartupVerifier verifier = verifier(server);

        // when
        // then
        assertThatIllegalStateException()
                .isThrownBy(() -> verifier.verify(true, true, false, true))
                .withMessageContaining("runtimeState=EXTENSION_INCOMPATIBLE")
                .withMessageContaining("reason=EXTENSION_FAILED_CODE_PAIR_INCONSISTENT")
                .satisfies(failure -> assertThat(failure.getMessage())
                        .doesNotContain("UNRELATED_EFFECTIVE_FAILURE"));
    }

    @Test
    void readyFailureCodeReportsClassifierReason() {
        // given
        MBeanServer server = MBeanServerFactory.createMBeanServer();
        AgentReadinessSnapshot ready = ReadinessTestFixtures.readySnapshot();
        AgentReadinessSnapshot.LifecycleState lifecycle = ready.lifecycleState();
        AgentReadinessSnapshot inconsistent = ReadinessTestFixtures.toBuilder(ready)
                .lifecycleState(new AgentReadinessSnapshot.LifecycleState(
                        "READY",
                        "ILLEGAL_READY_FAILURE",
                        "",
                        lifecycle.capabilities(),
                        lifecycle.missingRequired()))
                .build();
        ReadinessTestFixtures.register(server, () -> ReadinessTestFixtures.composite(inconsistent));
        AgentStartupVerifier verifier = verifier(server);

        // when
        // then
        assertThatIllegalStateException()
                .isThrownBy(() -> verifier.verify(true, true, false, true))
                .withMessageContaining("runtimeState=EXTENSION_INCOMPATIBLE")
                .withMessageContaining("reason=EXTENSION_READY_WITH_FAILURE_CODE")
                .satisfies(failure -> assertThat(failure.getMessage())
                        .doesNotContain("ILLEGAL_READY_FAILURE"));
    }

    @Test
    void initializingFailureCodeReportsClassifierReason() {
        // given
        MBeanServer server = MBeanServerFactory.createMBeanServer();
        AgentReadinessSnapshot initializing = ReadinessTestFixtures.initializingSnapshot();
        AgentReadinessSnapshot.LifecycleState lifecycle = initializing.lifecycleState();
        AgentReadinessSnapshot inconsistent = ReadinessTestFixtures.toBuilder(initializing)
                .lifecycleState(new AgentReadinessSnapshot.LifecycleState(
                        "INITIALIZING",
                        "ILLEGAL_INITIALIZING_FAILURE",
                        "",
                        lifecycle.capabilities(),
                        lifecycle.missingRequired()))
                .build();
        ReadinessTestFixtures.register(server, () -> ReadinessTestFixtures.composite(inconsistent));
        AgentStartupVerifier verifier = verifier(server);

        // when
        // then
        assertThatIllegalStateException()
                .isThrownBy(() -> verifier.verify(true, true, false, true))
                .withMessageContaining("runtimeState=EXTENSION_INCOMPATIBLE")
                .withMessageContaining("reason=EXTENSION_INITIALIZING_INCONSISTENT")
                .satisfies(failure -> assertThat(failure.getMessage())
                        .doesNotContain("ILLEGAL_INITIALIZING_FAILURE"));
    }

    @Test
    void modeForbiddenIsReturnedImmediatelyForInitializingAgentWithoutPolling() {
        // given
        MBeanServer server = MBeanServerFactory.createMBeanServer();
        AgentReadinessSnapshot initializing = ReadinessTestFixtures.initializingSnapshot();
        ReadinessTestFixtures.register(server, () -> ReadinessTestFixtures.composite(initializing));
        AtomicLong clock = new AtomicLong();
        AtomicInteger sleeps = new AtomicInteger();
        AgentStartupVerifier verifier = new AgentStartupVerifier(
                new AgentReadinessCoherenceGuard(server),
                Duration.ofSeconds(2),
                clock::get,
                nanos -> {
                    sleeps.incrementAndGet();
                    clock.addAndGet(nanos);
                },
                () -> ReadinessTestFixtures.APPLICATION_VERSION
        );

        // when
        // then
        assertThatIllegalStateException()
                .isThrownBy(() -> verifier.verify(false, true, false, false))
                .withMessageContaining("runtimeState=EXTENSION_INITIALIZING")
                .withMessageContaining("reason=CONTROLLED_AGENT_FORBIDDEN");
        assertThat(sleeps.get()).isZero();
        assertThat(clock.get()).isZero();
    }

    private static AgentStartupVerifier verifier(MBeanServer server) {
        return new AgentStartupVerifier(
                new AgentReadinessCoherenceGuard(server),
                Duration.ofSeconds(2),
                System::nanoTime,
                nanos -> {
                },
                () -> ReadinessTestFixtures.APPLICATION_VERSION
        );
    }

    @SuppressWarnings("SameParameterValue")
    private static AgentReadinessSnapshot withGeneration(AgentReadinessSnapshot source,
                                                         long evidenceGeneration,
                                                         String batchId) {
        AgentReadinessSnapshot.AnchorIdentity anchorIdentity = source.anchorIdentity();
        return ReadinessTestFixtures.toBuilder(source)
                .anchorIdentity(
                        new AgentReadinessSnapshot.AnchorIdentity(
                                anchorIdentity.agentInstanceId(),
                                evidenceGeneration,
                                batchId
                        )
                )
                .build();
    }

    private static AgentReadinessSnapshot withSchemaDigest(AgentReadinessSnapshot source,
                                                           String schemaDigest) {
        AgentReadinessSnapshot.ContractIdentity contractIdentity = source.contractIdentity();
        return ReadinessTestFixtures.toBuilder(source)
                .contractIdentity(
                        new AgentReadinessSnapshot.ContractIdentity(
                                contractIdentity.contractId(),
                                schemaDigest
                        )
                )
                .build();
    }
}
