package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionConfig;
import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;
import space.br1440.platform.tracing.otel.javaagent.jmx.security.RemoteJmxMutationRegistrationGate;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionCapability;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.PlatformScrubberFactory;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.ScrubbingRuntime;

import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigurationException;

final class AutoconfigureSessionFactory {

    private final PlatformScrubberFactory scrubberFactory;
    private final Function<ConfigProperties, RemoteJmxMutationRegistrationGate> remoteJmxExposurePolicyFactory;

    AutoconfigureSessionFactory(PlatformScrubberFactory scrubberFactory,
                                Function<ConfigProperties, RemoteJmxMutationRegistrationGate> remoteJmxExposurePolicyFactory) {
        this.scrubberFactory = Objects.requireNonNull(scrubberFactory, "scrubberFactory");
        this.remoteJmxExposurePolicyFactory = Objects.requireNonNull(
                remoteJmxExposurePolicyFactory,
                "Remote JMX exposure policy factory must not be null"
        );
    }

    static RemoteJmxMutationRegistrationGate currentProcessRemoteJmxPolicy(ConfigProperties ignored) {
        return RemoteJmxMutationRegistrationGate.fromCurrentProcessStartupArguments();
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
        RemoteJmxMutationRegistrationGate remoteJmxExposurePolicy = Objects.requireNonNull(
                remoteJmxExposurePolicyFactory.apply(config),
                "Remote JMX exposure policy factory must not return null"
        );

        AutoconfigureSession prepared = new AutoconfigureSession(
                UUID.randomUUID(), fingerprint, extensionConfig, policy, scrubbingRuntime, remoteJmxExposurePolicy
        );
        prepared.readiness().markInstalled(PlatformExtensionCapability.CONFIGURATION_LOADED);
        return prepared;
    }
}
