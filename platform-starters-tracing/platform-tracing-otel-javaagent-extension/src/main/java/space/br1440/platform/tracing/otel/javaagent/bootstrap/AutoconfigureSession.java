package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import java.util.Objects;
import java.util.UUID;

import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionConfig;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;
import space.br1440.platform.tracing.otel.javaagent.factory.PlatformExportProcessorFactory;
import space.br1440.platform.tracing.otel.javaagent.factory.PlatformSamplerFactory;
import space.br1440.platform.tracing.otel.javaagent.factory.PlatformSpanProcessorFactory;
import space.br1440.platform.tracing.otel.javaagent.jmx.registration.DomainMBeanRegistrar;
import space.br1440.platform.tracing.otel.javaagent.jmx.security.RemoteJmxExposurePolicy;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionReadiness;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.ScrubbingRuntime;

final class AutoconfigureSession {

    private final UUID sessionId;
    private final ConfigurationFingerprint fingerprint;
    private final ExtensionConfig extensionConfig;
    private final TracingSettingsSource policy;
    private final DomainMBeanRegistrar jmxRegistrar;
    private final ScrubbingRuntime scrubbingRuntime;
    private final PlatformSamplerFactory samplerFactory;
    private final PlatformSpanProcessorFactory spanProcessorFactory;
    private final PlatformExportProcessorFactory exportProcessorFactory;
    private final RemoteJmxExposurePolicy remoteJmxExposurePolicy;

    AutoconfigureSession(UUID sessionId,
                         ConfigurationFingerprint fingerprint,
                         ExtensionConfig extensionConfig,
                         TracingSettingsSource policy,
                         ScrubbingRuntime scrubbingRuntime,
                         RemoteJmxExposurePolicy remoteJmxExposurePolicy) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.fingerprint = Objects.requireNonNull(fingerprint, "fingerprint");
        this.extensionConfig = Objects.requireNonNull(extensionConfig, "extensionConfig");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.scrubbingRuntime = Objects.requireNonNull(scrubbingRuntime, "scrubbingRuntime");
        this.remoteJmxExposurePolicy = Objects.requireNonNull(remoteJmxExposurePolicy, "remoteJmxExposurePolicy");
        this.jmxRegistrar = new DomainMBeanRegistrar();
        this.samplerFactory = new PlatformSamplerFactory(jmxRegistrar);
        this.spanProcessorFactory = new PlatformSpanProcessorFactory(jmxRegistrar, scrubbingRuntime);
        this.exportProcessorFactory = new PlatformExportProcessorFactory(jmxRegistrar, scrubbingRuntime);
    }

    UUID sessionId() {
        return sessionId;
    }

    ConfigurationFingerprint fingerprint() {
        return fingerprint;
    }

    ExtensionConfig extensionConfig() {
        return extensionConfig;
    }

    TracingSettingsSource policy() {
        return policy;
    }

    DomainMBeanRegistrar jmxRegistrar() {
        return jmxRegistrar;
    }

    PlatformExtensionReadiness readiness() {
        return jmxRegistrar.extensionReadiness();
    }

    ScrubbingRuntime scrubbingRuntime() {
        return scrubbingRuntime;
    }

    PlatformSamplerFactory samplerFactory() {
        return samplerFactory;
    }

    PlatformSpanProcessorFactory spanProcessorFactory() {
        return spanProcessorFactory;
    }

    PlatformExportProcessorFactory exportProcessorFactory() {
        return exportProcessorFactory;
    }

    RemoteJmxExposurePolicy remoteJmxExposurePolicy() {
        return remoteJmxExposurePolicy;
    }
}
