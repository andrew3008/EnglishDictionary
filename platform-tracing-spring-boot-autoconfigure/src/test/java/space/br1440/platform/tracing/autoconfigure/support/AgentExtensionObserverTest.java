package space.br1440.platform.tracing.autoconfigure.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;
import javax.management.StandardMBean;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.autoconfigure.jmx.PlatformTracingJmxObjectNames;

class AgentExtensionObserverTest {

    private static final Set<String> COMPLETE_CAPABILITIES = Set.of(
            "CONFIGURATION_LOADED",
            "PLATFORM_SAMPLER_INSTALLED",
            "REQUIRED_SPAN_PROCESSORS_INSTALLED",
            "SANITIZER_INSTALLED",
            "PROPAGATION_HOOKS_INSTALLED",
            "SAFE_EXPORTER_INSTALLED",
            "EXPORT_PATH_PROTECTED");

    @Test
    void absenceDistinguishesNoAgentFromAgentWithoutExtension() {
        AgentExtensionObserver observer = new AgentExtensionObserver(MBeanServerFactory.createMBeanServer());

        assertThat(observer.observe(false, false, false).state())
                .isEqualTo(AgentRuntimeState.AGENT_MISSING);
        assertThat(observer.observe(false, true, false).state())
                .isEqualTo(AgentRuntimeState.EXTENSION_MISSING);
    }

    @Test
    void disabledDoesNotHideDualRuntime() throws Exception {
        MBeanServer server = serverWith(new ReadinessView("READY", 1, COMPLETE_CAPABILITIES, "", true));
        AgentExtensionObserver observer = new AgentExtensionObserver(server);

        assertThat(observer.observe(true, true, true).state())
                .isEqualTo(AgentRuntimeState.DUAL_SDK_DETECTED);
    }

    @Test
    void disabledFacadeDoesNotHideUnsafeAgentState() throws Exception {
        AgentExtensionObserver withoutEndpoint = new AgentExtensionObserver(
                MBeanServerFactory.createMBeanServer());
        AgentExtensionObserver readyEndpoint = new AgentExtensionObserver(
                serverWith(new ReadinessView("READY", 1, COMPLETE_CAPABILITIES, "", true)));

        assertThat(withoutEndpoint.observe(true, false, false).state())
                .isEqualTo(AgentRuntimeState.DISABLED);
        assertThat(withoutEndpoint.observe(true, true, false).state())
                .isEqualTo(AgentRuntimeState.EXTENSION_MISSING);
        assertThat(readyEndpoint.observe(true, true, false).state())
                .isEqualTo(AgentRuntimeState.AGENT_READY);
    }

    @Test
    void lifecycleAndProtocolAreValidated() throws Exception {
        assertThat(observe(new ReadinessView("INITIALIZING", 1, Set.of(), "", false)).state())
                .isEqualTo(AgentRuntimeState.EXTENSION_INITIALIZING);
        assertThat(observe(new ReadinessView("FAILED", 1, Set.of(), "SANITIZER_FAILED", false)).state())
                .isEqualTo(AgentRuntimeState.EXTENSION_FAILED);
        assertThat(observe(new ReadinessView("READY", 999, COMPLETE_CAPABILITIES, "", true)).state())
                .isEqualTo(AgentRuntimeState.EXTENSION_INCOMPATIBLE);
        assertThat(observe(new ReadinessView("READY", 1, Set.of("CONFIGURATION_LOADED"), "", true)).state())
                .isEqualTo(AgentRuntimeState.EXTENSION_INCOMPATIBLE);
        assertThat(observe(new ReadinessView("FAILED", 1, Set.of(), "", false)).state())
                .isEqualTo(AgentRuntimeState.EXTENSION_INCOMPATIBLE);
        assertThat(observe(new ReadinessView(
                "INITIALIZING", 1, COMPLETE_CAPABILITIES, "", true)).state())
                .isEqualTo(AgentRuntimeState.EXTENSION_INCOMPATIBLE);
    }

    @Test
    void readyRequiresCompleteConsistentSecureProfile() throws Exception {
        AgentExtensionDescriptor descriptor = observe(
                new ReadinessView("READY", 1, COMPLETE_CAPABILITIES, "", true));

        assertThat(descriptor.state()).isEqualTo(AgentRuntimeState.AGENT_READY);
        assertThat(descriptor.capabilities()).containsExactlyInAnyOrderElementsOf(COMPLETE_CAPABILITIES);
    }

    private static AgentExtensionDescriptor observe(ReadinessView view) throws Exception {
        return new AgentExtensionObserver(serverWith(view)).observe(false, true, false);
    }

    private static MBeanServer serverWith(ReadinessView view) throws Exception {
        MBeanServer server = MBeanServerFactory.createMBeanServer();
        server.registerMBean(
                new StandardMBean(view, ReadinessViewMBean.class),
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

        public String getExtensionVersion() {
            return "development";
        }

        public int getProtocolVersion() {
            return protocol;
        }

        public String getProfile() {
            return "platform-agent-secure-v1";
        }

        public String getLifecycleState() {
            return lifecycle;
        }

        public String getFailureCode() {
            return failureCode;
        }

        public String getFailureMessage() {
            return failureCode.isBlank() ? "" : "safe failure";
        }

        public String[] getCapabilities() {
            return capabilities.toArray(String[]::new);
        }

        public String[] getRequiredCapabilities() {
            return COMPLETE_CAPABILITIES.toArray(String[]::new);
        }

        public boolean isSanitizerInstalled() {
            return componentFlags;
        }

        public boolean isSamplerInstalled() {
            return componentFlags;
        }

        public boolean isRequiredSpanProcessorsInstalled() {
            return componentFlags;
        }

        public boolean isPropagationHooksInstalled() {
            return componentFlags;
        }

        public boolean isExportPathProtected() {
            return componentFlags;
        }
    }
}
