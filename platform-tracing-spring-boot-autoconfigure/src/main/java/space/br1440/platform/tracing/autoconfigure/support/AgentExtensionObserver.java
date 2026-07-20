package space.br1440.platform.tracing.autoconfigure.support;

import space.br1440.platform.tracing.autoconfigure.jmx.PlatformTracingJmxObjectNames;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Валидирует versioned readiness descriptor, опубликованный extension classloader'ом через JMX.
 */
public final class AgentExtensionObserver {

    static final int SUPPORTED_PROTOCOL_VERSION = 1;
    static final String SUPPORTED_PROFILE = "platform-agent-secure-v1";

    private static final Set<String> REQUIRED_CAPABILITIES = Set.of(
            "CONFIGURATION_LOADED",
            "PLATFORM_SAMPLER_INSTALLED",
            "REQUIRED_SPAN_PROCESSORS_INSTALLED",
            "SANITIZER_INSTALLED",
            "PROPAGATION_HOOKS_INSTALLED",
            "SAFE_EXPORTER_INSTALLED",
            "EXPORT_PATH_PROTECTED");

    private final MBeanServer server;

    public AgentExtensionObserver() {
        this(ManagementFactory.getPlatformMBeanServer());
    }

    AgentExtensionObserver(MBeanServer server) {
        this.server = server;
    }

    public AgentExtensionDescriptor observe(
            boolean configuredDisabled,
            boolean agentMarkerVisible,
            boolean userOpenTelemetryBeanPresent) {
        ObjectName readinessName = PlatformTracingJmxObjectNames.EXTENSION_READINESS;
        boolean endpointPresent = server.isRegistered(readinessName);

        if (userOpenTelemetryBeanPresent && (agentMarkerVisible || endpointPresent)) {
            return empty(AgentRuntimeState.DUAL_SDK_DETECTED, agentMarkerVisible, endpointPresent);
        }
        if (configuredDisabled && !agentMarkerVisible && !endpointPresent) {
            return empty(AgentRuntimeState.DISABLED, agentMarkerVisible, endpointPresent);
        }
        if (!endpointPresent) {
            return empty(
                    agentMarkerVisible ? AgentRuntimeState.EXTENSION_MISSING : AgentRuntimeState.AGENT_MISSING,
                    agentMarkerVisible,
                    false);
        }

        try {
            String version = stringAttribute(readinessName, "ExtensionVersion");
            int protocol = intAttribute(readinessName, "ProtocolVersion");
            String profile = stringAttribute(readinessName, "Profile");
            String lifecycle = stringAttribute(readinessName, "LifecycleState");
            String failureCode = stringAttribute(readinessName, "FailureCode");
            String failureMessage = stringAttribute(readinessName, "FailureMessage");
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
                    failureMessage,
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
                    safeMessage(malformedDescriptor),
                    Set.of());
        }
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
                || !failureCode.isBlank()
                || !capabilities.containsAll(REQUIRED_CAPABILITIES)
                || !booleanAttribute(readinessName, "SanitizerInstalled")
                || !booleanAttribute(readinessName, "SamplerInstalled")
                || !booleanAttribute(readinessName, "RequiredSpanProcessorsInstalled")
                || !booleanAttribute(readinessName, "PropagationHooksInstalled")
                || !booleanAttribute(readinessName, "ExportPathProtected")) {
            return AgentRuntimeState.EXTENSION_INCOMPATIBLE;
        }
        return AgentRuntimeState.AGENT_READY;
    }

    private static AgentExtensionDescriptor empty(
            AgentRuntimeState state,
            boolean agentMarkerVisible,
            boolean endpointPresent) {
        return new AgentExtensionDescriptor(
                state, agentMarkerVisible, endpointPresent, "", -1, "", "", "", "", Set.of());
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

    private static String safeMessage(Exception failure) {
        String message = failure.getMessage();
        String value = failure.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : ": " + message);
        return value.length() <= 512 ? value : value.substring(0, 512);
    }
}
