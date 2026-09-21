package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionConfig;
import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;
import space.br1440.platform.tracing.otel.javaagent.jmx.security.RemoteJmxMutationRegistrationAssessor;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionCapability;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.PlatformScrubberFactory;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.ScrubbingRuntime;

import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigurationException;

final class AutoconfigureSessionFactory {

    private final PlatformScrubberFactory scrubberFactory;
    private final Function<ConfigProperties, RemoteJmxMutationRegistrationAssessor> remoteJmxAssessorFactory;

    AutoconfigureSessionFactory(PlatformScrubberFactory scrubberFactory,
                                Function<ConfigProperties, RemoteJmxMutationRegistrationAssessor> remoteJmxAssessorFactory) {
        this.scrubberFactory = Objects.requireNonNull(scrubberFactory, "scrubberFactory");
        this.remoteJmxAssessorFactory = Objects.requireNonNull(
                remoteJmxAssessorFactory,
                "Remote JMX assessor factory must not be null"
        );
    }

    static RemoteJmxMutationRegistrationAssessor currentProcessRemoteJmxAssessor(ConfigProperties ignored) {
        return RemoteJmxMutationRegistrationAssessor.fromCurrentProcess();
    }

    AutoconfigureSession create(ConfigProperties config, ConfigurationFingerprint fingerprint) {
        ExtensionConfig extensionConfig = new ExtensionConfig(config);
        if (!extensionConfig.scrubbing().enabled()) {
            String message = "%s=false is forbidden by the secure Agent profile"
                    .formatted(ExtensionPropertyNames.SCRUBBING_ENABLED);

            throw new ConfigurationException(message);
        }

        TracingSettingsSource policy = TracingSettingsSource.fromStartup(extensionConfig);
        ScrubbingRuntime scrubbingRuntime = scrubberFactory.buildRuntime(extensionConfig, config);
        RemoteJmxMutationRegistrationAssessor remoteJmxMutationRegistrationAssessor = Objects.requireNonNull(
                remoteJmxAssessorFactory.apply(config),
                "Remote JMX assessor factory must not return null"
        );

        AutoconfigureSession prepared = new AutoconfigureSession(
                UUID.randomUUID(),
                fingerprint,
                extensionConfig,
                policy,
                scrubbingRuntime,
                remoteJmxMutationRegistrationAssessor
        );
        prepared.readiness().markInstalled(PlatformExtensionCapability.CONFIGURATION_LOADED);
        return prepared;
    }
}
