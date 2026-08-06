package space.br1440.platform.tracing.autoconfigure.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import javax.management.InstanceNotFoundException;
import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;
import javax.management.ObjectName;
import javax.management.StandardMBean;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.PlatformTracingJmxObjectNames;

/**
 * Слайс 0: фиксирует текущее (в т.ч. нежелательное) поведение {@link AgentExtensionObserver} до
 * начала рефакторинга. Тесты — характеризационные: они пинуют факты, а не проверяют желаемый
 * дизайн. Часть зафиксированного поведения намеренно изменяется в Slice 2/3 (batch-контракт,
 * точное версионное равенство, JMX exception matrix); при изменении поведения соответствующий тест
 * здесь переписывается с явным diff, а не молча остаётся зелёным.
 * <p>
 * Ноль изменений production-кода в этом коммите: файл только описывает существующее поведение
 * {@link AgentExtensionObserver}.
 */
class AgentExtensionObserverCharacterizationTest {

    private static final Set<String> COMPLETE_CAPABILITIES = Set.of(
            "CONFIGURATION_LOADED",
            "PLATFORM_SAMPLER_INSTALLED",
            "REQUIRED_SPAN_PROCESSORS_INSTALLED",
            "SANITIZER_INSTALLED",
            "PROPAGATION_HOOKS_INSTALLED",
            "SAFE_EXPORTER_INSTALLED",
            "EXPORT_PATH_PROTECTED");

    /**
     * Baseline for A-01: packaged runtime version proof depends on {@code Implementation-Version}
     * being present in the built JAR manifest. Today the autoconfigure Gradle module does not set
     * this manifest attribute, and running from compiled classes (as this test does) never carries
     * one either way. This pins the "missing version" starting point that Slice 3 must handle via
     * fail-closed rather than the current silent {@code null -> "development"} fallback.
     */
    @Test
    void applicationPackageHasNoImplementationVersionInThisRuntime() {
        Package observerPackage = AgentExtensionObserver.class.getPackage();
        assertThat(observerPackage.getImplementationVersion()).isNull();
    }

    /**
     * Baseline for the one-sided {@code "development"} wildcard: today, if the *application* side
     * resolves to {@code "development"} (this test JVM's normal case, since there is no manifest),
     * the check accepts an extension reporting ANY other version string. This asymmetric wildcard
     * is removed in Slice 3 in favor of an explicit two-sided {@code development}/{@code development}
     * unit seam.
     */
    @Test
    void developmentApplicationVersionWildcardsAnyExtensionVersionToday() throws Exception {
        MBeanServer server = MBeanServerFactory.createMBeanServer();
        server.registerMBean(
                new StandardMBean(
                        readinessView("READY", 1, COMPLETE_CAPABILITIES, "", true)
                                .withExtensionVersion("9.9.9-not-development"),
                        AgentExtensionObserverTest.ReadinessViewMBean.class),
                PlatformTracingJmxObjectNames.EXTENSION_READINESS);

        AgentExtensionDescriptor descriptor = new AgentExtensionObserver(server)
                .observe(AgentDeploymentExpectation.REQUIRED, true, false, true);

        assertThat(descriptor.state())
                .as("mismatched non-development extension version is accepted today because the "
                        + "application side resolves to \"development\" in this test JVM")
                .isEqualTo(AgentRuntimeState.AGENT_READY);
    }

    /**
     * Baseline for A-19: today, any {@link Exception} thrown while reading readiness attributes
     * (including {@link InstanceNotFoundException} raised by an MBean disappearing between the
     * {@code isRegistered} check and the attribute read) is caught by one broad
     * {@code catch (Exception)} and collapsed into the same terminal outcome as any other malformed
     * descriptor: {@code EXTENSION_INCOMPATIBLE} / {@code MALFORMED_READINESS_DESCRIPTOR}. There is
     * no distinct "endpoint disappeared" outcome yet; Slice 3 introduces
     * {@code READINESS_ENDPOINT_DISAPPEARED} as its own failure code on the same frozen state.
     */
    @Test
    void mbeanDisappearingBetweenIsRegisteredAndReadIsCollapsedIntoMalformedDescriptorToday()
            throws Exception {
        MBeanServer empty = MBeanServerFactory.createMBeanServer();
        MBeanServer alwaysRegisteredButAbsent = alwaysReportRegistered(empty);

        AgentExtensionDescriptor descriptor = new AgentExtensionObserver(alwaysRegisteredButAbsent)
                .observe(AgentDeploymentExpectation.REQUIRED, true, false, true);

        assertThat(descriptor.state()).isEqualTo(AgentRuntimeState.EXTENSION_INCOMPATIBLE);
        assertThat(descriptor.failureCode()).isEqualTo("MALFORMED_READINESS_DESCRIPTOR");
    }

    /**
     * Baseline for A-19: a {@link SecurityException} thrown by the MBean getter (surfaced by JMX as
     * {@code RuntimeMBeanException}) is classified identically to the disappearance case above —
     * the current code has no exception-type-specific handling at all.
     */
    @Test
    void securityExceptionDuringAttributeReadIsCollapsedIntoMalformedDescriptorToday() throws Exception {
        MBeanServer server = MBeanServerFactory.createMBeanServer();
        server.registerMBean(
                new StandardMBean(
                        readinessView("READY", 1, COMPLETE_CAPABILITIES, "", true)
                                .throwingOnExtensionVersion(new SecurityException("access denied")),
                        AgentExtensionObserverTest.ReadinessViewMBean.class),
                PlatformTracingJmxObjectNames.EXTENSION_READINESS);

        AgentExtensionDescriptor descriptor = new AgentExtensionObserver(server)
                .observe(AgentDeploymentExpectation.REQUIRED, true, false, true);

        assertThat(descriptor.state()).isEqualTo(AgentRuntimeState.EXTENSION_INCOMPATIBLE);
        assertThat(descriptor.failureCode()).isEqualTo("MALFORMED_READINESS_DESCRIPTOR");
    }

    /**
     * Baseline for the {@code FailureMessage} attribute: today it IS read from JMX on every
     * successful descriptor path (one extra round trip per {@code observe()} call) but its value is
     * discarded immediately — {@link AgentExtensionDescriptor} has no field for it. Slice 2 removes
     * this read entirely (it is a recording-only proof channel, never requested by the application).
     */
    @Test
    void failureMessageAttributeIsReadThenDiscardedToday() throws Exception {
        AtomicInteger failureMessageReads = new AtomicInteger();
        MBeanServer server = MBeanServerFactory.createMBeanServer();
        server.registerMBean(
                new StandardMBean(
                        readinessView("FAILED", 1, Set.of(), "SANITIZER_FAILED", false)
                                .countingFailureMessageReadsInto(failureMessageReads),
                        AgentExtensionObserverTest.ReadinessViewMBean.class),
                PlatformTracingJmxObjectNames.EXTENSION_READINESS);

        AgentExtensionDescriptor descriptor = new AgentExtensionObserver(server)
                .observe(AgentDeploymentExpectation.REQUIRED, true, false, true);

        assertThat(failureMessageReads.get()).isEqualTo(1);
        assertThat(descriptor.failureCode()).isEqualTo("SANITIZER_FAILED");
    }

    /**
     * Baseline for the elapsed-time computation used by {@code awaitStartupDecision}: the current
     * {@code deadline - now} subtraction pattern (as opposed to a banned {@code now > deadline}
     * absolute comparison) already tolerates a {@code nanoTime()} wraparound around
     * {@link Long#MAX_VALUE}. This pins that near-wrap behavior stays correct as later slices touch
     * elapsed-time computation (A-20).
     */
    @Test
    void forbiddenPollingToleratesNanoTimeWrapAroundLongMaxValue() {
        AtomicLong clock = new AtomicLong(Long.MAX_VALUE - 50);
        AgentExtensionObserver observer = new AgentExtensionObserver(
                MBeanServerFactory.createMBeanServer(),
                Duration.ofNanos(100),
                clock::get,
                clock::addAndGet);

        AgentExtensionDescriptor result = observer.awaitStartupDecision(
                AgentDeploymentExpectation.FORBIDDEN, false, false, false);

        assertThat(result.state()).isEqualTo(AgentRuntimeState.DISABLED);
        assertThat(clock.get()).isEqualTo(Long.MAX_VALUE - 50 + 100);
    }

    /**
     * Baseline for the FORBIDDEN one-shot rule that Slice 3 replaces: today {@code FORBIDDEN}
     * always waits the full configured window (production constant: 2s, shortened in tests via
     * {@code platform.tracing.test.startup-readiness-timeout-ms}) before returning
     * {@code DISABLED}, even though no presence signal will ever appear after Spring bootstrap
     * completes. There is no early exit.
     */
    @Test
    void forbiddenWaitsFullTimeoutWindowRatherThanOneShotCheckToday() {
        AtomicLong clock = new AtomicLong();
        AtomicInteger sleeps = new AtomicInteger();
        AgentExtensionObserver observer = new AgentExtensionObserver(
                MBeanServerFactory.createMBeanServer(),
                Duration.ofNanos(1_000),
                clock::get,
                nanos -> {
                    sleeps.incrementAndGet();
                    clock.addAndGet(nanos);
                });

        AgentExtensionDescriptor result = observer.awaitStartupDecision(
                AgentDeploymentExpectation.FORBIDDEN, false, false, false);

        assertThat(result.state()).isEqualTo(AgentRuntimeState.DISABLED);
        assertThat(clock.get()).isEqualTo(1_000L);
        assertThat(sleeps.get()).isGreaterThan(0);
    }

    /**
     * Baseline for the production status/health sticky-snapshot behavior: {@code observe()} itself
     * has no memory between calls — every invocation re-derives state fresh from the current JMX
     * readiness view. The "sticky startup snapshot" referenced by the normative plan is a property
     * of {@code TracingDeploymentDiagnostics} (constructed once at startup and held immutably by its
     * consumers), not of the observer. This test pins that {@code observe()} reacts immediately to a
     * change in the underlying MBean rather than caching a prior result.
     */
    @Test
    void observeHasNoStickinessAndReactsToLiveMBeanChangesToday() throws Exception {
        MBeanServer server = MBeanServerFactory.createMBeanServer();
        ObjectName readinessName = PlatformTracingJmxObjectNames.EXTENSION_READINESS;
        server.registerMBean(
                new StandardMBean(
                        new AgentExtensionObserverTest.ReadinessView(
                                "INITIALIZING", 1, Set.of(), "", false),
                        AgentExtensionObserverTest.ReadinessViewMBean.class),
                readinessName);
        AgentExtensionObserver observer = new AgentExtensionObserver(server);

        assertThat(observer.observe(AgentDeploymentExpectation.REQUIRED, true, false, true).state())
                .isEqualTo(AgentRuntimeState.EXTENSION_INITIALIZING);

        server.unregisterMBean(readinessName);
        server.registerMBean(
                new StandardMBean(
                        new AgentExtensionObserverTest.ReadinessView(
                                "READY", 1, COMPLETE_CAPABILITIES, "", true),
                        AgentExtensionObserverTest.ReadinessViewMBean.class),
                readinessName);

        assertThat(observer.observe(AgentDeploymentExpectation.REQUIRED, true, false, true).state())
                .isEqualTo(AgentRuntimeState.AGENT_READY);
    }

    /**
     * Wraps a real {@link MBeanServer} so that {@code isRegistered} always reports {@code true}
     * regardless of actual registration state, while every other call (including attribute reads)
     * delegates unchanged. Used to deterministically reproduce "endpoint disappeared between the
     * presence check and the read" without a timing-dependent race between two threads.
     */
    private static ConfigurableReadinessView readinessView(
            String lifecycle, int protocol, Set<String> capabilities, String failureCode, boolean componentFlags) {
        return new ConfigurableReadinessView(lifecycle, protocol, capabilities, failureCode, componentFlags);
    }

    /**
     * Non-final variant of {@link AgentExtensionObserverTest.ReadinessView} that allows individual
     * attribute getters to be overridden per test, since the shared fixture class is intentionally
     * kept {@code final} for its own test file.
     */
    private static final class ConfigurableReadinessView
            implements AgentExtensionObserverTest.ReadinessViewMBean {
        private final String lifecycle;
        private final int protocol;
        private final Set<String> capabilities;
        private final String failureCode;
        private final boolean componentFlags;
        private String extensionVersion = "development";
        private RuntimeException extensionVersionFailure;
        private AtomicInteger failureMessageReadCounter;

        ConfigurableReadinessView(
                String lifecycle, int protocol, Set<String> capabilities, String failureCode,
                boolean componentFlags) {
            this.lifecycle = lifecycle;
            this.protocol = protocol;
            this.capabilities = capabilities;
            this.failureCode = failureCode;
            this.componentFlags = componentFlags;
        }

        ConfigurableReadinessView withExtensionVersion(String version) {
            this.extensionVersion = version;
            return this;
        }

        ConfigurableReadinessView throwingOnExtensionVersion(RuntimeException failure) {
            this.extensionVersionFailure = failure;
            return this;
        }

        ConfigurableReadinessView countingFailureMessageReadsInto(AtomicInteger counter) {
            this.failureMessageReadCounter = counter;
            return this;
        }

        @Override
        public String getExtensionVersion() {
            if (extensionVersionFailure != null) {
                throw extensionVersionFailure;
            }
            return extensionVersion;
        }

        @Override
        public int getProtocolVersion() {
            return protocol;
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
        public String getFailureMessage() {
            if (failureMessageReadCounter != null) {
                failureMessageReadCounter.incrementAndGet();
            }
            return failureCode.isBlank() ? "" : "safe failure";
        }

        @Override
        public String[] getCapabilities() {
            return capabilities.toArray(String[]::new);
        }

        @Override
        public String[] getRequiredCapabilities() {
            return COMPLETE_CAPABILITIES.toArray(String[]::new);
        }

        @Override
        public boolean isSanitizerInstalled() {
            return componentFlags;
        }

        @Override
        public boolean isSamplerInstalled() {
            return componentFlags;
        }

        @Override
        public boolean isRequiredSpanProcessorsInstalled() {
            return componentFlags;
        }

        @Override
        public boolean isPropagationHooksInstalled() {
            return componentFlags;
        }

        @Override
        public boolean isExportPathProtected() {
            return componentFlags;
        }
    }

    private static MBeanServer alwaysReportRegistered(MBeanServer delegate) {
        InvocationHandler handler = (proxy, method, args) -> {
            if ("isRegistered".equals(method.getName())) {
                return true;
            }
            try {
                return method.invoke(delegate, args);
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw e.getCause();
            }
        };
        return (MBeanServer) Proxy.newProxyInstance(
                MBeanServer.class.getClassLoader(),
                new Class<?>[] {MBeanServer.class},
                handler);
    }
}
