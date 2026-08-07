package space.br1440.platform.tracing.autoconfigure.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.management.ManagementFactory;
import java.util.HashSet;
import java.util.Set;

import javax.management.MBeanInfo;
import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;
import javax.management.ObjectName;
import javax.management.StandardMBean;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.PlatformTracingJmxObjectNames;
import space.br1440.platform.tracing.otel.javaagent.jmx.readiness.PlatformExtensionReadinessControl;
import space.br1440.platform.tracing.otel.javaagent.jmx.readiness.PlatformExtensionReadinessMBean;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionReadiness;

class AgentReadinessProbeTest {

    private static final Set<String> COMPLETE_CAPABILITIES = Set.of(
            "CONFIGURATION_LOADED",
            "PLATFORM_SAMPLER_INSTALLED",
            "REQUIRED_SPAN_PROCESSORS_INSTALLED",
            "SANITIZER_INSTALLED",
            "PROPAGATION_HOOKS_INSTALLED",
            "SAFE_EXPORTER_INSTALLED",
            "EXPORT_PATH_PROTECTED");

    @Test
    void endpointRegisteredReflectsLiveMBeanServerState() throws Exception {
        MBeanServer server = MBeanServerFactory.createMBeanServer();
        AgentReadinessProbe probe = new AgentReadinessProbe(server);

        assertThat(probe.endpointRegistered(PlatformTracingJmxObjectNames.EXTENSION_READINESS)).isFalse();

        registerReadinessView(server, "READY", 1, COMPLETE_CAPABILITIES, "", true);

        assertThat(probe.endpointRegistered(PlatformTracingJmxObjectNames.EXTENSION_READINESS)).isTrue();
    }

    @Test
    void readDecodesAllTwelveAttributesInASingleBatchCallIntoAnImmutableSnapshot() throws Exception {
        CountingMBeanServer counting = new CountingMBeanServer(
                registerReadinessView(
                        MBeanServerFactory.createMBeanServer(), "READY", 1, COMPLETE_CAPABILITIES, "", true));
        AgentReadinessProbe probe = new AgentReadinessProbe(counting.asProxy());

        AgentReadinessProbe.ProbeResult result = probe.read(PlatformTracingJmxObjectNames.EXTENSION_READINESS);

        assertThat(counting.getAttributeCalls).as("no single-attribute fallback reads").isZero();
        assertThat(counting.getAttributesCalls).isEqualTo(1);
        assertThat(result).isInstanceOf(AgentReadinessProbe.ProbeResult.Present.class);
        AgentReadinessSnapshot snapshot = ((AgentReadinessProbe.ProbeResult.Present) result).snapshot();
        assertThat(snapshot.protocol().extensionVersion()).isEqualTo("development");
        assertThat(snapshot.protocol().protocolVersion()).isEqualTo(1);
        assertThat(snapshot.protocol().profile()).isEqualTo("platform-agent-secure-v1");
        assertThat(snapshot.status().lifecycle()).isEqualTo("READY");
        assertThat(snapshot.status().failureCode()).isEmpty();
        assertThat(snapshot.capabilities().advertised())
                .containsExactlyInAnyOrderElementsOf(COMPLETE_CAPABILITIES);
        assertThat(snapshot.capabilities().required())
                .containsExactlyInAnyOrderElementsOf(COMPLETE_CAPABILITIES);
        assertThat(snapshot.installFlags().isComplete()).isTrue();

        assertThatThrownBy(() -> snapshot.capabilities().advertised().add("INJECTED"))
                .as("capabilities set returned by the probe must be defensively unmodifiable")
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> snapshot.capabilities().required().add("INJECTED"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void readNeverRequestsFailureMessage() throws Exception {
        assertThat(AgentReadinessProbe.REQUESTED_ATTRIBUTE_NAMES).doesNotContain("FailureMessage");
        assertThat(AgentReadinessProbe.REQUESTED_ATTRIBUTE_NAMES).hasSize(12);
    }

    @Test
    void readIsRejectedAsIncompleteWhenTheBatchReturnsFewerAttributesThanRequested() throws Exception {
        MBeanServer server = MBeanServerFactory.createMBeanServer();
        server.registerMBean(
                new StandardMBean(new PartiallyDeclaredReadinessView(), PartialReadinessViewMBean.class),
                PlatformTracingJmxObjectNames.EXTENSION_READINESS);
        AgentReadinessProbe probe = new AgentReadinessProbe(server);

        AgentReadinessProbe.ProbeResult result = probe.read(PlatformTracingJmxObjectNames.EXTENSION_READINESS);

        assertThat(result).isInstanceOf(AgentReadinessProbe.ProbeResult.Rejected.class);
        assertThat(((AgentReadinessProbe.ProbeResult.Rejected) result).reason())
                .isEqualTo(AgentReadinessProbe.ProbeReason.INCOMPLETE_DESCRIPTOR);
    }

    @Test
    void readIsRejectedAsMalformedWhenAnAttributeHasTheWrongType() throws Exception {
        MBeanServer server = MBeanServerFactory.createMBeanServer();
        server.registerMBean(
                new StandardMBean(new WrongTypeReadinessView(), WrongTypeReadinessViewMBean.class),
                PlatformTracingJmxObjectNames.EXTENSION_READINESS);
        AgentReadinessProbe probe = new AgentReadinessProbe(server);

        AgentReadinessProbe.ProbeResult result = probe.read(PlatformTracingJmxObjectNames.EXTENSION_READINESS);

        assertThat(result).isInstanceOf(AgentReadinessProbe.ProbeResult.Rejected.class);
        assertThat(((AgentReadinessProbe.ProbeResult.Rejected) result).reason())
                .isEqualTo(AgentReadinessProbe.ProbeReason.MALFORMED_DESCRIPTOR);
    }

    @Test
    void readIsRejectedAsMalformedWhenAnAttributeIsNull() throws Exception {
        MBeanServer server = MBeanServerFactory.createMBeanServer();
        server.registerMBean(
                new StandardMBean(new NullExtensionVersionReadinessView(), WrongTypeReadinessViewMBean.class),
                PlatformTracingJmxObjectNames.EXTENSION_READINESS);
        AgentReadinessProbe probe = new AgentReadinessProbe(server);

        AgentReadinessProbe.ProbeResult result = probe.read(PlatformTracingJmxObjectNames.EXTENSION_READINESS);

        assertThat(result).isInstanceOf(AgentReadinessProbe.ProbeResult.Rejected.class);
    }

    @Test
    void readIsRejectedWhenEndpointDisappearsBetweenIsRegisteredAndTheBatchRead() throws Exception {
        MBeanServer empty = MBeanServerFactory.createMBeanServer();
        MBeanServer alwaysRegisteredButAbsent = FakeMBeanServers.alwaysReportRegistered(empty);
        AgentReadinessProbe probe = new AgentReadinessProbe(alwaysRegisteredButAbsent);

        assertThat(probe.endpointRegistered(PlatformTracingJmxObjectNames.EXTENSION_READINESS)).isTrue();
        AgentReadinessProbe.ProbeResult result = probe.read(PlatformTracingJmxObjectNames.EXTENSION_READINESS);

        assertThat(result).isInstanceOf(AgentReadinessProbe.ProbeResult.Rejected.class);
        assertThat(((AgentReadinessProbe.ProbeResult.Rejected) result).reason())
                .isEqualTo(AgentReadinessProbe.ProbeReason.ENDPOINT_DISAPPEARED);
    }

    @Test
    void readIsIndeterminateOnSecurityExceptionDuringTheBatchRead() throws Exception {
        MBeanServer server = MBeanServerFactory.createMBeanServer();
        SecurityException accessDenied = new SecurityException("classified");
        MBeanServer denying = FakeMBeanServers.throwingOnGetAttributes(server, accessDenied);
        AgentReadinessProbe probe = new AgentReadinessProbe(denying);

        AgentReadinessProbe.ProbeResult result = probe.read(PlatformTracingJmxObjectNames.EXTENSION_READINESS);

        assertThat(result).isInstanceOf(AgentReadinessProbe.ProbeResult.Indeterminate.class);
        AgentReadinessProbe.ProbeResult.Indeterminate indeterminate =
                (AgentReadinessProbe.ProbeResult.Indeterminate) result;
        assertThat(indeterminate.reason()).isEqualTo(AgentReadinessProbe.ProbeReason.ACCESS_DENIED);
        assertThat(indeterminate.cause()).isSameAs(accessDenied);
    }

    @Test
    void readIsIndeterminateOnReflectionExceptionDuringTheBatchRead() throws Exception {
        MBeanServer server = MBeanServerFactory.createMBeanServer();
        javax.management.ReflectionException reflectionFailure =
                new javax.management.ReflectionException(new NoSuchMethodException("boom"));
        MBeanServer failing = FakeMBeanServers.throwingOnGetAttributes(server, reflectionFailure);
        AgentReadinessProbe probe = new AgentReadinessProbe(failing);

        AgentReadinessProbe.ProbeResult result = probe.read(PlatformTracingJmxObjectNames.EXTENSION_READINESS);

        assertThat(result).isInstanceOf(AgentReadinessProbe.ProbeResult.Indeterminate.class);
        assertThat(((AgentReadinessProbe.ProbeResult.Indeterminate) result).reason())
                .isEqualTo(AgentReadinessProbe.ProbeReason.INVOCATION_FAILED);
    }

    /**
     * Set-arithmetic proof (not a hardcoded string) that the physical readiness MBean exposes
     * exactly 13 attributes, the probe requests exactly 12, and the delta is exactly
     * {@code FailureMessage} in both directions.
     */
    @Test
    void probeRequestsExactlyTheAgentAttributesMinusFailureMessage() throws Exception {
        MBeanServer server = ManagementFactory.getPlatformMBeanServer();
        ObjectName temporaryName = new ObjectName("space.br1440.platform.tracing.test:type=ReadinessMBeanInfoProbe");
        server.registerMBean(
                new StandardMBean(
                        new PlatformExtensionReadinessControl(new PlatformExtensionReadiness()),
                        PlatformExtensionReadinessMBean.class),
                temporaryName);
        try {
            MBeanInfo info = server.getMBeanInfo(temporaryName);
            Set<String> agentAttributeNames = new HashSet<>();
            for (var attribute : info.getAttributes()) {
                agentAttributeNames.add(attribute.getName());
            }

            assertThat(agentAttributeNames).hasSize(13);
            assertThat(AgentReadinessProbe.REQUESTED_ATTRIBUTE_NAMES).hasSize(12);

            Set<String> agentMinusRequested = new HashSet<>(agentAttributeNames);
            agentMinusRequested.removeAll(AgentReadinessProbe.REQUESTED_ATTRIBUTE_NAMES);
            assertThat(agentMinusRequested).containsExactly("FailureMessage");

            Set<String> requestedMinusAgent = new HashSet<>(AgentReadinessProbe.REQUESTED_ATTRIBUTE_NAMES);
            requestedMinusAgent.removeAll(agentAttributeNames);
            assertThat(requestedMinusAgent).isEmpty();
        } finally {
            server.unregisterMBean(temporaryName);
        }
    }

    private static MBeanServer registerReadinessView(
            MBeanServer server,
            String lifecycle,
            int protocol,
            Set<String> capabilities,
            String failureCode,
            boolean componentFlags) throws Exception {
        server.registerMBean(
                new StandardMBean(
                        new ReadinessView(lifecycle, protocol, capabilities, failureCode, componentFlags),
                        ReadinessViewMBean.class),
                PlatformTracingJmxObjectNames.EXTENSION_READINESS);
        return server;
    }

    public interface ReadinessViewMBean {
        String getExtensionVersion();

        int getProtocolVersion();

        String getProfile();

        String getLifecycleState();

        String getFailureCode();

        String getFailureMessage();

        String[] getCapabilities();

        String[] getRequiredCapabilities();

        boolean isSanitizerInstalled();

        boolean isSamplerInstalled();

        boolean isRequiredSpanProcessorsInstalled();

        boolean isPropagationHooksInstalled();

        boolean isExportPathProtected();
    }

    public static final class ReadinessView implements ReadinessViewMBean {
        private final String lifecycle;
        private final int protocol;
        private final Set<String> capabilities;
        private final String failureCode;
        private final boolean componentFlags;

        ReadinessView(
                String lifecycle,
                int protocol,
                Set<String> capabilities,
                String failureCode,
                boolean componentFlags) {
            this.lifecycle = lifecycle;
            this.protocol = protocol;
            this.capabilities = capabilities;
            this.failureCode = failureCode;
            this.componentFlags = componentFlags;
        }

        @Override
        public String getExtensionVersion() {
            return "development";
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

    public interface PartialReadinessViewMBean {
        String getExtensionVersion();
    }

    public static final class PartiallyDeclaredReadinessView implements PartialReadinessViewMBean {
        @Override
        public String getExtensionVersion() {
            return "development";
        }
    }

    public interface WrongTypeReadinessViewMBean {
        Object getExtensionVersion();

        int getProtocolVersion();

        String getProfile();

        String getLifecycleState();

        String getFailureCode();

        String[] getCapabilities();

        String[] getRequiredCapabilities();

        boolean isSanitizerInstalled();

        boolean isSamplerInstalled();

        boolean isRequiredSpanProcessorsInstalled();

        boolean isPropagationHooksInstalled();

        boolean isExportPathProtected();
    }

    public static class WrongTypeReadinessView implements WrongTypeReadinessViewMBean {
        @Override
        public Object getExtensionVersion() {
            return 42;
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
            return "READY";
        }

        @Override
        public String getFailureCode() {
            return "";
        }

        @Override
        public String[] getCapabilities() {
            return new String[0];
        }

        @Override
        public String[] getRequiredCapabilities() {
            return new String[0];
        }

        @Override
        public boolean isSanitizerInstalled() {
            return true;
        }

        @Override
        public boolean isSamplerInstalled() {
            return true;
        }

        @Override
        public boolean isRequiredSpanProcessorsInstalled() {
            return true;
        }

        @Override
        public boolean isPropagationHooksInstalled() {
            return true;
        }

        @Override
        public boolean isExportPathProtected() {
            return true;
        }
    }

    public static final class NullExtensionVersionReadinessView extends WrongTypeReadinessView {
        @Override
        public Object getExtensionVersion() {
            return null;
        }
    }
}
