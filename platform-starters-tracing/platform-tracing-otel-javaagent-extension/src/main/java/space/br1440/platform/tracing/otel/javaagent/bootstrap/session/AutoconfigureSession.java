package space.br1440.platform.tracing.otel.javaagent.bootstrap.session;

import java.util.Objects;
import java.util.UUID;

import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionConfig;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;
import space.br1440.platform.tracing.otel.javaagent.factory.PlatformExportProcessorFactory;
import space.br1440.platform.tracing.otel.javaagent.factory.PlatformSamplerFactory;
import space.br1440.platform.tracing.otel.javaagent.factory.PlatformSpanProcessorFactory;
import space.br1440.platform.tracing.otel.javaagent.jmx.registration.PlatformTracingMBeanRegistrar;
import space.br1440.platform.tracing.otel.javaagent.jmx.security.RemoteJmxMutationRegistrationAssessor;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionReadiness;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.ScrubbingRuntime;

public final class AutoconfigureSession {

    private final UUID sessionId;
    private final ConfigurationFingerprint fingerprint;
    private final ExtensionConfig extensionConfig;
    private final TracingSettingsSource policy;
    private final PlatformTracingMBeanRegistrar jmxRegistrar;
    private final ScrubbingRuntime scrubbingRuntime;
    private final PlatformSamplerFactory samplerFactory;
    private final PlatformSpanProcessorFactory spanProcessorFactory;
    private final PlatformExportProcessorFactory exportProcessorFactory;
    private final RemoteJmxMutationRegistrationAssessor remoteJmxMutationRegistrationAssessor;

    public AutoconfigureSession(UUID sessionId,
                         ConfigurationFingerprint fingerprint,
                         ExtensionConfig extensionConfig,
                         TracingSettingsSource policy,
                         ScrubbingRuntime scrubbingRuntime,
                         RemoteJmxMutationRegistrationAssessor remoteJmxMutationRegistrationAssessor) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.fingerprint = Objects.requireNonNull(fingerprint, "fingerprint");
        this.extensionConfig = Objects.requireNonNull(extensionConfig, "extensionConfig");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.scrubbingRuntime = Objects.requireNonNull(scrubbingRuntime, "scrubbingRuntime");
        this.remoteJmxMutationRegistrationAssessor = Objects.requireNonNull(
                remoteJmxMutationRegistrationAssessor,
                "remoteJmxMutationRegistrationAssessor"
        );
        this.jmxRegistrar = new PlatformTracingMBeanRegistrar();
        this.samplerFactory = new PlatformSamplerFactory(jmxRegistrar);
        this.spanProcessorFactory = new PlatformSpanProcessorFactory(jmxRegistrar, scrubbingRuntime);
        this.exportProcessorFactory = new PlatformExportProcessorFactory(jmxRegistrar, scrubbingRuntime);
    }

    public UUID sessionId() {
        return sessionId;
    }

    public ConfigurationFingerprint fingerprint() {
        return fingerprint;
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

    public PlatformSamplerFactory samplerFactory() {
        return samplerFactory;
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
}
