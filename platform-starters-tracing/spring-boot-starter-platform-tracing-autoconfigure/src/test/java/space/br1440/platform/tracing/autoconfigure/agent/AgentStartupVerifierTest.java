package space.br1440.platform.tracing.autoconfigure.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;
import javax.management.StandardMBean;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.PlatformTracingJmxObjectNames;

/**
 * Behavioral tests for {@link AgentStartupVerifier}: one decision per call, no hidden retries
 * beyond the documented {@code REQUIRED}+{@code EXTENSION_INITIALIZING} poll and the FORBIDDEN
 * one-shot check.
 */
class AgentStartupVerifierTest {

    private static final Set<String> COMPLETE_CAPABILITIES = Set.of(
            "CONFIGURATION_LOADED",
            "PLATFORM_SAMPLER_INSTALLED",
            "REQUIRED_SPAN_PROCESSORS_INSTALLED",
            "SANITIZER_INSTALLED",
            "PROPAGATION_HOOKS_INSTALLED",
            "SAFE_EXPORTER_INSTALLED",
            "EXPORT_PATH_PROTECTED");

    @Test
    void requiredFailsClosedWhenAgentIsCompletelyAbsent() {
        AgentStartupVerifier verifier = verifier(MBeanServerFactory.createMBeanServer());

        assertThatIllegalStateException()
                .isThrownBy(() -> verifier.verify(true, false, false, false))
                .withMessageContaining("state=AGENT_MISSING");
    }

    @Test
    void requiredDistinguishesAgentPresentWithoutExtension() {
        AgentStartupVerifier verifier = verifier(MBeanServerFactory.createMBeanServer());

        assertThatIllegalStateException()
                .isThrownBy(() -> verifier.verify(true, true, false, true))
                .withMessageContaining("state=EXTENSION_MISSING");
    }

    @Test
    void requiredSucceedsOnlyWithAgentReadyAndReturnsNestedDiagnostics() throws Exception {
        MBeanServer server = readyServer();
        AgentStartupVerifier verifier = verifier(server);

        TracingDeploymentDiagnostics diagnostics = verifier.verify(true, true, false, true);

        assertThat(diagnostics.tracingEnabled()).isTrue();
        assertThat(diagnostics.runtimeState()).isEqualTo(AgentRuntimeState.AGENT_READY);
        assertThat(diagnostics.agentMarkerVisible()).isTrue();
        assertThat(diagnostics.agentReadiness().endpointPresent()).isTrue();
        assertThat(diagnostics.agentReadiness().capabilities())
                .containsExactlyInAnyOrderElementsOf(COMPLETE_CAPABILITIES);
    }

    @Test
    void dualSdkIsRejectedEvenWithTracingDisabled() {
        AgentStartupVerifier verifier = verifier(MBeanServerFactory.createMBeanServer());

        assertThatIllegalStateException()
                .isThrownBy(() -> verifier.verify(false, false, true, false))
                .withMessageContaining("state=DUAL_SDK_DETECTED");
    }

    @Test
    void forbiddenIsOneShotAndNeverSleepsWhenSignalsAreAbsent() {
        AtomicLong clock = new AtomicLong();
        AtomicInteger sleeps = new AtomicInteger();
        AgentStartupVerifier verifier = new AgentStartupVerifier(
                MBeanServerFactory.createMBeanServer(),
                Duration.ofSeconds(2),
                clock::get,
                nanos -> {
                    sleeps.incrementAndGet();
                    clock.addAndGet(nanos);
                },
                "1.0.0");

        TracingDeploymentDiagnostics diagnostics = verifier.verify(false, false, false, false);

        assertThat(diagnostics.runtimeState()).isEqualTo(AgentRuntimeState.DISABLED);
        assertThat(clock.get()).isZero();
        assertThat(sleeps.get()).isZero();
    }

    @Test
    void forbiddenWithAnyPresenceSignalFailsClosedRatherThanReturningDisabled() {
        AgentStartupVerifier verifier = verifier(MBeanServerFactory.createMBeanServer());

        assertThatIllegalStateException()
                .isThrownBy(() -> verifier.verify(false, true, false, false))
                .withMessageContaining("state=EXTENSION_MISSING");
    }

    @Test
    void requiredPollsOnlyWhileInitializingThenReturnsReadyOnceCapabilitiesComplete() throws Exception {
        MBeanServer server = MBeanServerFactory.createMBeanServer();
        MutableReadinessView view = new MutableReadinessView("INITIALIZING", Set.of());
        server.registerMBean(
                new StandardMBean(view, MutableReadinessViewMBean.class),
                PlatformTracingJmxObjectNames.EXTENSION_READINESS);

        AtomicLong clock = new AtomicLong();
        AtomicInteger sleeps = new AtomicInteger();
        AgentStartupVerifier verifier = new AgentStartupVerifier(
                server,
                Duration.ofSeconds(2),
                clock::get,
                nanos -> {
                    sleeps.incrementAndGet();
                    clock.addAndGet(nanos);
                    view.lifecycle = "READY";
                    view.capabilities = COMPLETE_CAPABILITIES;
                },
                "1.0.0");

        TracingDeploymentDiagnostics diagnostics = verifier.verify(true, true, false, true);

        assertThat(diagnostics.runtimeState()).isEqualTo(AgentRuntimeState.AGENT_READY);
        assertThat(sleeps.get()).isEqualTo(1);
    }

    @Test
    void requiredInitializingPastDeadlineFailsClosedWithReadinessTimeoutFailureCode() {
        MBeanServer server = MBeanServerFactory.createMBeanServer();
        try {
            server.registerMBean(
                    new StandardMBean(new MutableReadinessView("INITIALIZING", Set.of()),
                            MutableReadinessViewMBean.class),
                    PlatformTracingJmxObjectNames.EXTENSION_READINESS);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }

        AtomicLong clock = new AtomicLong();
        AgentStartupVerifier verifier = new AgentStartupVerifier(
                server, Duration.ofNanos(100), clock::get, clock::addAndGet, "1.0.0");

        assertThatIllegalStateException()
                .isThrownBy(() -> verifier.verify(true, true, false, true))
                .withMessageContaining("EXTENSION_READINESS_TIMEOUT");
    }

    /**
     * Gate C1 (r5 plan &sect;5, S5): {@code AgentStartupVerifier} must reject on the new
     * cross-SPI {@code BootstrapCoordinator} failure codes (activate-in-{@code
     * afterAgent}, r5 plan &sect;3/&sect;4) purely opaquely — every non-blank {@code FailureCode}
     * under a {@code FAILED} lifecycle is treated the same way, with no per-code switch on the
     * Spring side. This is a regression against silently swallowing an unrecognized failure code.
     */
    @Test
    void newClassifiedCoordinatorCodesAreRejectedAsEXTENSION_FAILED() throws Exception {
        for (String failureCode : Set.of(
                "PLATFORM_ACP_NOT_EXECUTED", "DUPLICATE_ACTIVATION", "ACTIVATION_FAILED",
                "CONFIGURATION_INITIALIZATION_FAILED", "DIAGNOSTIC_MBEAN_REGISTRATION_FAILED",
                "SECOND_SDK_BUILD", "CONFIGURATION_SPLIT_BRAIN")) {
            MBeanServer server = MBeanServerFactory.createMBeanServer();
            server.registerMBean(
                    new StandardMBean(
                            new MutableReadinessView("FAILED", Set.of(), failureCode),
                            MutableReadinessViewMBean.class),
                    PlatformTracingJmxObjectNames.EXTENSION_READINESS);
            AgentStartupVerifier verifier = verifier(server);

            assertThatIllegalStateException()
                    .as("failureCode=" + failureCode)
                    .isThrownBy(() -> verifier.verify(true, true, false, true))
                    .withMessageContaining("state=EXTENSION_FAILED")
                    .withMessageContaining(failureCode);
        }
    }

    @Test
    void endpointDisappearanceBeforeFirstSuccessIsTreatedAsMissingNotAnAnomaly() throws Exception {
        MBeanServer alwaysRegisteredButAbsent =
                FakeMBeanServers.alwaysReportRegistered(MBeanServerFactory.createMBeanServer());
        AgentStartupVerifier verifier = verifier(alwaysRegisteredButAbsent);

        assertThatIllegalStateException()
                .isThrownBy(() -> verifier.verify(true, true, false, true))
                .withMessageContaining("state=EXTENSION_MISSING");
    }

    @Test
    void securityExceptionDuringReadIsClassifiedAsAccessDeniedFailureCode() {
        MBeanServer denying = FakeMBeanServers.throwingOnGetAttributes(
                MBeanServerFactory.createMBeanServer(), new SecurityException("denied"));
        MBeanServer alwaysRegistered = FakeMBeanServers.alwaysReportRegistered(denying);
        AgentStartupVerifier verifier = verifier(alwaysRegistered);

        assertThatIllegalStateException()
                .isThrownBy(() -> verifier.verify(true, true, false, true))
                .withMessageContaining("READINESS_ACCESS_DENIED");
    }

    @Test
    void forbiddenNanoTimeWrapAroundLongMaxValueIsToleratedByElapsedSubtraction() {
        AtomicLong clock = new AtomicLong(Long.MAX_VALUE - 50);
        AgentStartupVerifier verifier = new AgentStartupVerifier(
                MBeanServerFactory.createMBeanServer(),
                Duration.ofNanos(100),
                clock::get,
                clock::addAndGet,
                "1.0.0");

        TracingDeploymentDiagnostics diagnostics = verifier.verify(false, false, false, false);

        assertThat(diagnostics.runtimeState()).isEqualTo(AgentRuntimeState.DISABLED);
    }

    private static AgentStartupVerifier verifier(MBeanServer server) {
        return new AgentStartupVerifier(
                server, Duration.ofSeconds(2), System::nanoTime, nanos -> { }, "1.0.0");
    }

    private static MBeanServer readyServer() throws Exception {
        MBeanServer server = MBeanServerFactory.createMBeanServer();
        server.registerMBean(
                new StandardMBean(
                        new MutableReadinessView("READY", COMPLETE_CAPABILITIES),
                        MutableReadinessViewMBean.class),
                PlatformTracingJmxObjectNames.EXTENSION_READINESS);
        return server;
    }

    public interface MutableReadinessViewMBean {
        String getExtensionVersion();

        int getProtocolVersion();

        String getProfile();

        String getLifecycleState();

        String getFailureCode();

        String[] getCapabilities();
    }

    /**
     * Mutable so a sleeper stub can simulate the agent transitioning out of
     * {@code INITIALIZING} between polling attempts, without a real background thread.
     */
    public static final class MutableReadinessView implements MutableReadinessViewMBean {
        volatile String lifecycle;
        volatile Set<String> capabilities;
        volatile String failureCode = "";

        MutableReadinessView(String lifecycle, Set<String> capabilities) {
            this.lifecycle = lifecycle;
            this.capabilities = capabilities;
        }

        MutableReadinessView(String lifecycle, Set<String> capabilities, String failureCode) {
            this(lifecycle, capabilities);
            this.failureCode = failureCode;
        }

        @Override
        public String getExtensionVersion() {
            return "1.0.0";
        }

        @Override
        public int getProtocolVersion() {
            return 1;
        }

        @Override
        public String getProfile() {
            return "platform-agent-secure-v1";
        }

        @Override
        public String getLifecycleState() {
            return lifecycle;
        }

        @Override
        public String getFailureCode() {
            return failureCode;
        }

        @Override
        public String[] getCapabilities() {
            return capabilities.toArray(String[]::new);
        }
    }
}
