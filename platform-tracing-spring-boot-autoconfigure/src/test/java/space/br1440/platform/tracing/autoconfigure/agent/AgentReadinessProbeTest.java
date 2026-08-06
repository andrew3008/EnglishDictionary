package space.br1440.platform.tracing.autoconfigure.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;

import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;
import javax.management.StandardMBean;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.PlatformTracingJmxObjectNames;

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
    void readDecodesAllAttributesIntoAnImmutableSnapshot() throws Exception {
        MBeanServer server = registerReadinessView(
                MBeanServerFactory.createMBeanServer(), "READY", 1, COMPLETE_CAPABILITIES, "", true);
        AgentReadinessProbe probe = new AgentReadinessProbe(server);

        AgentReadinessProbe.ProbeResult result = probe.read(PlatformTracingJmxObjectNames.EXTENSION_READINESS);

        assertThat(result).isInstanceOf(AgentReadinessProbe.ProbeResult.Present.class);
        AgentReadinessSnapshot snapshot = ((AgentReadinessProbe.ProbeResult.Present) result).snapshot();
        assertThat(snapshot.extensionVersion()).isEqualTo("development");
        assertThat(snapshot.protocolVersion()).isEqualTo(1);
        assertThat(snapshot.profile()).isEqualTo("platform-agent-secure-v1");
        assertThat(snapshot.lifecycle()).isEqualTo("READY");
        assertThat(snapshot.failureCode()).isEmpty();
        assertThat(snapshot.capabilities()).containsExactlyInAnyOrderElementsOf(COMPLETE_CAPABILITIES);
        assertThat(snapshot.sanitizerInstalled()).isTrue();
        assertThat(snapshot.samplerInstalled()).isTrue();
        assertThat(snapshot.requiredSpanProcessorsInstalled()).isTrue();
        assertThat(snapshot.propagationHooksInstalled()).isTrue();
        assertThat(snapshot.exportPathProtected()).isTrue();

        assertThatThrownBy(() -> snapshot.capabilities().add("INJECTED"))
                .as("capabilities set returned by the probe must be defensively unmodifiable")
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void readIsMalformedWhenAttributeIsMissing() throws Exception {
        MBeanServer server = MBeanServerFactory.createMBeanServer();
        server.registerMBean(
                new StandardMBean(new PartiallyDeclaredReadinessView(), PartialReadinessViewMBean.class),
                PlatformTracingJmxObjectNames.EXTENSION_READINESS);
        AgentReadinessProbe probe = new AgentReadinessProbe(server);

        AgentReadinessProbe.ProbeResult result = probe.read(PlatformTracingJmxObjectNames.EXTENSION_READINESS);

        assertThat(result).isInstanceOf(AgentReadinessProbe.ProbeResult.Malformed.class);
        assertThat(((AgentReadinessProbe.ProbeResult.Malformed) result).reason())
                .isEqualTo("MALFORMED_READINESS_DESCRIPTOR");
    }

    @Test
    void readIsMalformedWhenAttributeHasTheWrongType() throws Exception {
        MBeanServer server = MBeanServerFactory.createMBeanServer();
        server.registerMBean(
                new StandardMBean(new WrongTypeReadinessView(), WrongTypeReadinessViewMBean.class),
                PlatformTracingJmxObjectNames.EXTENSION_READINESS);
        AgentReadinessProbe probe = new AgentReadinessProbe(server);

        AgentReadinessProbe.ProbeResult result = probe.read(PlatformTracingJmxObjectNames.EXTENSION_READINESS);

        assertThat(result).isInstanceOf(AgentReadinessProbe.ProbeResult.Malformed.class);
    }

    @Test
    void readIsMalformedWhenAttributeIsNull() throws Exception {
        MBeanServer server = MBeanServerFactory.createMBeanServer();
        server.registerMBean(
                new StandardMBean(new NullExtensionVersionReadinessView(), WrongTypeReadinessViewMBean.class),
                PlatformTracingJmxObjectNames.EXTENSION_READINESS);
        AgentReadinessProbe probe = new AgentReadinessProbe(server);

        AgentReadinessProbe.ProbeResult result = probe.read(PlatformTracingJmxObjectNames.EXTENSION_READINESS);

        assertThat(result).isInstanceOf(AgentReadinessProbe.ProbeResult.Malformed.class);
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
                        new AgentExtensionObserverTest.ReadinessView(
                                lifecycle, protocol, capabilities, failureCode, componentFlags),
                        AgentExtensionObserverTest.ReadinessViewMBean.class),
                PlatformTracingJmxObjectNames.EXTENSION_READINESS);
        return server;
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

        String getFailureMessage();

        String[] getCapabilities();

        boolean isSanitizerInstalled();

        boolean isSamplerInstalled();

        boolean isRequiredSpanProcessorsInstalled();

        boolean isPropagationHooksInstalled();

        boolean isExportPathProtected();
    }

    public static final class WrongTypeReadinessView implements WrongTypeReadinessViewMBean {
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
        public String getFailureMessage() {
            return "";
        }

        @Override
        public String[] getCapabilities() {
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

    public static final class NullExtensionVersionReadinessView implements WrongTypeReadinessViewMBean {
        @Override
        public Object getExtensionVersion() {
            return null;
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
        public String getFailureMessage() {
            return "";
        }

        @Override
        public String[] getCapabilities() {
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
}
