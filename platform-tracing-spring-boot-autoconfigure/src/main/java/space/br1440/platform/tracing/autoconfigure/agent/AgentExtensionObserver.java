package space.br1440.platform.tracing.autoconfigure.agent;

import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.PlatformTracingJmxObjectNames;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.LockSupport;
import java.util.function.LongSupplier;

public final class AgentExtensionObserver {

    static final int SUPPORTED_PROTOCOL_VERSION = 1;
    static final String SUPPORTED_PROFILE = "platform-agent-secure-v1";

    static final Duration STARTUP_READINESS_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration READINESS_POLL_INTERVAL = Duration.ofMillis(25);

    private static final Set<String> REQUIRED_CAPABILITIES = Set.of(
            "CONFIGURATION_LOADED",
            "PLATFORM_SAMPLER_INSTALLED",
            "REQUIRED_SPAN_PROCESSORS_INSTALLED",
            "SANITIZER_INSTALLED",
            "PROPAGATION_HOOKS_INSTALLED",
            "SAFE_EXPORTER_INSTALLED",
            "EXPORT_PATH_PROTECTED"
    );

    @FunctionalInterface
    interface Sleeper {
        void sleep(long nanos);
    }

    private final MBeanServer server;
    private final Duration startupReadinessTimeout;
    private final LongSupplier nanoTime;
    private final Sleeper sleeper;

    public AgentExtensionObserver() {
        this(ManagementFactory.getPlatformMBeanServer());
    }

    AgentExtensionObserver(MBeanServer server) {
        this(server, resolveStartupTimeout(), System::nanoTime, LockSupport::parkNanos);
    }

    static Duration resolveStartupTimeout() {
        String override = System.getProperty("platform.tracing.test.startup-readiness-timeout-ms");
        if (override == null || override.isBlank()) {
            return STARTUP_READINESS_TIMEOUT;
        }

        long millis = Long.parseLong(override.trim());
        if (millis <= 0L) {
            throw new IllegalStateException(
                    "platform.tracing.test.startup-readiness-timeout-ms must be positive");
        }

        return Duration.ofMillis(millis);
    }

    AgentExtensionObserver(MBeanServer server,
                           Duration startupReadinessTimeout,
                           LongSupplier nanoTime,
                           Sleeper sleeper) {
        this.server = Objects.requireNonNull(server, "server");
        this.startupReadinessTimeout = Objects.requireNonNull(startupReadinessTimeout, "startupReadinessTimeout");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");

        if (startupReadinessTimeout.isNegative() || startupReadinessTimeout.isZero()) {
            throw new IllegalArgumentException("startupReadinessTimeout must be positive");
        }
    }

    public AgentExtensionDescriptor observe(AgentDeploymentExpectation expectation,
                                            boolean agentMarkerVisible,
                                            boolean userOpenTelemetryBeanPresent,
                                            boolean globalOpenTelemetrySet) {
        Objects.requireNonNull(expectation, "expectation");
        ObjectName readinessName = PlatformTracingJmxObjectNames.EXTENSION_READINESS;
        boolean endpointPresent = server.isRegistered(readinessName);

        if (userOpenTelemetryBeanPresent
                || (globalOpenTelemetrySet && !agentMarkerVisible && !endpointPresent)) {
            return empty(
                    AgentRuntimeState.DUAL_SDK_DETECTED,
                    agentMarkerVisible,
                    endpointPresent,
                    "APPLICATION_RUNTIME_DETECTED");
        }
        if (!endpointPresent) {
            return empty(
                    agentMarkerVisible ? AgentRuntimeState.EXTENSION_MISSING : AgentRuntimeState.AGENT_MISSING,
                    agentMarkerVisible,
                    false,
                    agentMarkerVisible ? "PLATFORM_EXTENSION_MISSING" : "CONTROLLED_AGENT_MISSING");
        }

        try {
            String version = stringAttribute(readinessName, "ExtensionVersion");
            int protocol = intAttribute(readinessName, "ProtocolVersion");
            String profile = stringAttribute(readinessName, "Profile");
            String lifecycle = stringAttribute(readinessName, "LifecycleState");
            String failureCode = stringAttribute(readinessName, "FailureCode");
            stringAttribute(readinessName, "FailureMessage");
            Set<String> capabilities = stringSetAttribute(readinessName, "Capabilities");

            AgentRuntimeState state = classify(
                    version, protocol, profile, lifecycle, failureCode, capabilities, readinessName);
            return new AgentExtensionDescriptor(
                    state,
                    agentMarkerVisible,
                    true,
                    version,
                    protocol,
                    profile,
                    lifecycle,
                    failureCode,
                    capabilities);
        } catch (Exception malformedDescriptor) {
            return new AgentExtensionDescriptor(
                    AgentRuntimeState.EXTENSION_INCOMPATIBLE,
                    agentMarkerVisible,
                    true,
                    "",
                    -1,
                    "",
                    "",
                    "MALFORMED_READINESS_DESCRIPTOR",
                    Set.of());
        }
    }

    /**
     * Ожидает решение в течение startup poll window.
     *
     * <ul>
     *   <li>{@link AgentDeploymentExpectation#REQUIRED} — ждёт выхода из {@code INITIALIZING},
     *       затем возвращает классифицированное состояние;</li>
     *   <li>{@link AgentDeploymentExpectation#FORBIDDEN} — ждёт полный timeout при нулевых
     *       presence-сигналах и только после deadline возвращает {@code DISABLED};
     *       любой presence-сигнал классифицируется как non-{@code DISABLED}.</li>
     * </ul>
     */
    public AgentExtensionDescriptor awaitStartupDecision(
            AgentDeploymentExpectation expectation,
            boolean agentMarkerVisible,
            boolean userOpenTelemetryBeanPresent,
            boolean globalOpenTelemetrySet) {
        Objects.requireNonNull(expectation, "expectation");
        long deadline = nanoTime.getAsLong() + startupReadinessTimeout.toNanos();
        AgentExtensionDescriptor descriptor;
        do {
            descriptor = observe(
                    expectation,
                    agentMarkerVisible,
                    userOpenTelemetryBeanPresent,
                    globalOpenTelemetrySet);

            if (expectation == AgentDeploymentExpectation.FORBIDDEN
                    && isAbsentSignals(descriptor, globalOpenTelemetrySet, userOpenTelemetryBeanPresent)) {
                long remaining = deadline - nanoTime.getAsLong();
                if (remaining <= 0) {
                    return empty(AgentRuntimeState.DISABLED, false, false, "");
                }
                sleeper.sleep(Math.min(remaining, READINESS_POLL_INTERVAL.toNanos()));
                continue;
            }

            if (descriptor.state() != AgentRuntimeState.EXTENSION_INITIALIZING) {
                return descriptor;
            }
            long remaining = deadline - nanoTime.getAsLong();
            if (remaining <= 0) {
                return withFailureCode(descriptor, "EXTENSION_READINESS_TIMEOUT");
            }
            sleeper.sleep(Math.min(remaining, READINESS_POLL_INTERVAL.toNanos()));
        } while (true);
    }

    private static boolean isAbsentSignals(
            AgentExtensionDescriptor descriptor,
            boolean globalOpenTelemetrySet,
            boolean userOpenTelemetryBeanPresent) {
        return !userOpenTelemetryBeanPresent
                && !globalOpenTelemetrySet
                && !descriptor.agentMarkerVisible()
                && !descriptor.readinessEndpointPresent()
                && (descriptor.state() == AgentRuntimeState.AGENT_MISSING
                || descriptor.state() == AgentRuntimeState.DISABLED);
    }

    private AgentRuntimeState classify(
            String extensionVersion,
            int protocol,
            String profile,
            String lifecycle,
            String failureCode,
            Set<String> capabilities,
            ObjectName readinessName) throws Exception {
        if (protocol != SUPPORTED_PROTOCOL_VERSION
                || !SUPPORTED_PROFILE.equals(profile)
                || !versionsCompatible(extensionVersion, applicationVersion())) {
            return AgentRuntimeState.EXTENSION_INCOMPATIBLE;
        }
        if ("FAILED".equals(lifecycle)) {
            return failureCode.isBlank()
                    ? AgentRuntimeState.EXTENSION_INCOMPATIBLE
                    : AgentRuntimeState.EXTENSION_FAILED;
        }
        if ("INITIALIZING".equals(lifecycle)) {
            return failureCode.isBlank() && !capabilities.containsAll(REQUIRED_CAPABILITIES)
                    ? AgentRuntimeState.EXTENSION_INITIALIZING
                    : AgentRuntimeState.EXTENSION_INCOMPATIBLE;
        }
        if (!"READY".equals(lifecycle)
                || !failureCode.isBlank()) {
            return AgentRuntimeState.EXTENSION_INCOMPATIBLE;
        }
        if (!capabilities.containsAll(REQUIRED_CAPABILITIES)
                || !booleanAttribute(readinessName, "SanitizerInstalled")
                || !booleanAttribute(readinessName, "SamplerInstalled")
                || !booleanAttribute(readinessName, "RequiredSpanProcessorsInstalled")
                || !booleanAttribute(readinessName, "PropagationHooksInstalled")
                || !booleanAttribute(readinessName, "ExportPathProtected")) {
            return AgentRuntimeState.CAPABILITY_MISSING;
        }
        return AgentRuntimeState.AGENT_READY;
    }

    private static AgentExtensionDescriptor empty(
            AgentRuntimeState state,
            boolean agentMarkerVisible,
            boolean endpointPresent,
            String failureCode) {
        return new AgentExtensionDescriptor(
                state, agentMarkerVisible, endpointPresent, "", -1, "", "", failureCode, Set.of());
    }

    private static AgentExtensionDescriptor withFailureCode(
            AgentExtensionDescriptor descriptor,
            String failureCode) {
        return new AgentExtensionDescriptor(
                descriptor.state(),
                descriptor.agentMarkerVisible(),
                descriptor.readinessEndpointPresent(),
                descriptor.extensionVersion(),
                descriptor.protocolVersion(),
                descriptor.profile(),
                descriptor.lifecycle(),
                failureCode,
                descriptor.capabilities());
    }

    private String stringAttribute(ObjectName name, String attribute) throws Exception {
        Object value = server.getAttribute(name, attribute);
        if (value instanceof String string) {
            return string;
        }
        throw new IllegalStateException(attribute + " must be String");
    }

    private int intAttribute(ObjectName name, String attribute) throws Exception {
        Object value = server.getAttribute(name, attribute);
        if (value instanceof Integer integer) {
            return integer;
        }
        throw new IllegalStateException(attribute + " must be Integer");
    }

    private boolean booleanAttribute(ObjectName name, String attribute) throws Exception {
        Object value = server.getAttribute(name, attribute);
        if (value instanceof Boolean bool) {
            return bool;
        }
        throw new IllegalStateException(attribute + " must be Boolean");
    }

    private Set<String> stringSetAttribute(ObjectName name, String attribute) throws Exception {
        Object value = server.getAttribute(name, attribute);
        if (value instanceof String[] array) {
            return new LinkedHashSet<>(Arrays.asList(array));
        }
        throw new IllegalStateException(attribute + " must be String[]");
    }

    private static boolean versionsCompatible(String extensionVersion, String applicationVersion) {
        return "development".equals(extensionVersion)
                || "development".equals(applicationVersion)
                || extensionVersion.equals(applicationVersion);
    }

    private static String applicationVersion() {
        Package observerPackage = AgentExtensionObserver.class.getPackage();
        String version = observerPackage == null ? null : observerPackage.getImplementationVersion();
        return version == null || version.isBlank() ? "development" : version;
    }

}
