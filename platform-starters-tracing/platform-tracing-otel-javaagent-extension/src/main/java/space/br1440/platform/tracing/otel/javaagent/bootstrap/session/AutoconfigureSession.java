package space.br1440.platform.tracing.otel.javaagent.bootstrap.session;

import java.util.Objects;

import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionConfig;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;
import space.br1440.platform.tracing.otel.javaagent.factory.PlatformExportProcessorFactory;
import space.br1440.platform.tracing.otel.javaagent.factory.PlatformSpanProcessorFactory;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.jmx.readiness.ExtensionRuntimeEvidence;
import space.br1440.platform.tracing.otel.javaagent.jmx.registration.PlatformTracingMBeanRegistrar;
import space.br1440.platform.tracing.otel.javaagent.jmx.security.RemoteJmxMutationRegistrationAssessor;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionReadiness;
import space.br1440.platform.tracing.otel.javaagent.sampler.SafeSampler;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime.ScrubbingRuntime;

public final class AutoconfigureSession implements AutoCloseable {

    private final AgentInstanceId agentInstanceId;
    private final ExtensionConfig extensionConfig;
    private final TracingSettingsSource policy;
    private final SafeSampler sampler;
    private final ExtensionRuntimeEvidence evidence;
    private final PlatformTracingMBeanRegistrar jmxRegistrar;
    private final ScrubbingRuntime scrubbingRuntime;
    private final PlatformSpanProcessorFactory spanProcessorFactory;
    private final PlatformExportProcessorFactory exportProcessorFactory;
    private final RemoteJmxMutationRegistrationAssessor remoteJmxMutationRegistrationAssessor;

    public AutoconfigureSession(AgentInstanceId agentInstanceId,
                                ExtensionConfig extensionConfig,
                                TracingSettingsSource policy,
                                SafeSampler sampler,
                                ExtensionRuntimeEvidence evidence,
                                PlatformTracingMBeanRegistrar jmxRegistrar,
                                ScrubbingRuntime scrubbingRuntime,
                                RemoteJmxMutationRegistrationAssessor remoteJmxMutationRegistrationAssessor) {
        this.agentInstanceId = Objects.requireNonNull(agentInstanceId, "agentInstanceId");
        this.extensionConfig = Objects.requireNonNull(extensionConfig, "extensionConfig");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.sampler = Objects.requireNonNull(sampler, "sampler");
        this.evidence = Objects.requireNonNull(evidence, "evidence");
        this.jmxRegistrar = Objects.requireNonNull(jmxRegistrar, "jmxRegistrar");
        this.scrubbingRuntime = Objects.requireNonNull(scrubbingRuntime, "scrubbingRuntime");
        this.remoteJmxMutationRegistrationAssessor = Objects.requireNonNull(
                remoteJmxMutationRegistrationAssessor,
                "remoteJmxMutationRegistrationAssessor"
        );
        requireSharedAgentInstanceId(this.agentInstanceId, this.policy, this.jmxRegistrar);
        this.spanProcessorFactory = new PlatformSpanProcessorFactory(jmxRegistrar, scrubbingRuntime);
        this.exportProcessorFactory = new PlatformExportProcessorFactory(jmxRegistrar, scrubbingRuntime);
    }

    public AgentInstanceId agentInstanceId() {
        return agentInstanceId;
    }

    public ExtensionConfig extensionConfig() {
        return extensionConfig;
    }

    public TracingSettingsSource policy() {
        return policy;
    }

    public PlatformTracingMBeanRegistrar jmxRegistrar() {
        return jmxRegistrar;
    }

    public PlatformExtensionReadiness readiness() {
        return jmxRegistrar.extensionReadiness();
    }

    public ScrubbingRuntime scrubbingRuntime() {
        return scrubbingRuntime;
    }

    public SafeSampler sampler() {
        return sampler;
    }

    public ExtensionRuntimeEvidence evidence() {
        return evidence;
    }

    public PlatformSpanProcessorFactory spanProcessorFactory() {
        return spanProcessorFactory;
    }

    public PlatformExportProcessorFactory exportProcessorFactory() {
        return exportProcessorFactory;
    }

    public RemoteJmxMutationRegistrationAssessor remoteJmxMutationRegistrationAssessor() {
        return remoteJmxMutationRegistrationAssessor;
    }

    @Override
    public void close() {
        scrubbingRuntime.close();
    }

    private static void requireSharedAgentInstanceId(AgentInstanceId expected,
                                                     TracingSettingsSource policy,
                                                     PlatformTracingMBeanRegistrar registrar) {
        if (!expected.equals(registrar.agentInstanceId())) {
            throw new IllegalArgumentException(
                    "Autoconfigure session and JMX registrar agentInstanceId values must match"
            );
        }

        if (!expected.equals(policy.current().agentInstanceId())) {
            throw new IllegalArgumentException(
                    "Autoconfigure session and tracing settings agentInstanceId values must match"
            );
        }
    }
}
