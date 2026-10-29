package space.br1440.platform.tracing.otel.javaagent.bootstrap.session;

import java.util.Objects;
import java.util.function.Function;

import space.br1440.platform.tracing.otel.javaagent.bootstrap.resources.BootstrapCleanupFailures;
import space.br1440.platform.tracing.otel.javaagent.configuration.ConfigurationPropertyException;
import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionConfig;
import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames;
import space.br1440.platform.tracing.otel.javaagent.configuration.PropagatorConfigurationContract;
import space.br1440.platform.tracing.otel.javaagent.configuration.SamplingExtensionConfig;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.jmx.readiness.ExtensionRuntimeEvidence;
import space.br1440.platform.tracing.otel.javaagent.jmx.registration.PlatformTracingMBeanRegistrar;
import space.br1440.platform.tracing.otel.javaagent.jmx.security.RemoteJmxMutationRegistrationAssessor;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionCapability;
import space.br1440.platform.tracing.otel.javaagent.sampler.CompositeSampler;
import space.br1440.platform.tracing.otel.javaagent.sampler.SafeSampler;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.assembly.PlatformScrubberFactory;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime.ScrubbingRuntime;
import space.br1440.platform.tracing.runtime.sampling.model.SamplingPolicySnapshot;
import space.br1440.platform.tracing.runtime.sampling.properties.SamplingPolicySnapshotFactory;

import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigurationException;

public final class AutoconfigureSessionFactory {

    private final PlatformScrubberFactory scrubberFactory;
    private final Function<ConfigProperties, RemoteJmxMutationRegistrationAssessor> remoteJmxAssessorFactory;

    public AutoconfigureSessionFactory(PlatformScrubberFactory scrubberFactory,
                                       Function<ConfigProperties, RemoteJmxMutationRegistrationAssessor> remoteJmxAssessorFactory) {
        this.scrubberFactory = Objects.requireNonNull(scrubberFactory, "scrubberFactory");
        this.remoteJmxAssessorFactory = Objects.requireNonNull(
                remoteJmxAssessorFactory,
                "Remote JMX assessor factory must not be null"
        );
    }

    public static RemoteJmxMutationRegistrationAssessor currentProcessRemoteJmxAssessor(ConfigProperties ignored) {
        return RemoteJmxMutationRegistrationAssessor.fromCurrentProcess();
    }

    public AutoconfigureSession create(ConfigProperties config,
                                       PlatformTracingMBeanRegistrar jmxRegistrar,
                                       AgentInstanceId agentInstanceId,
                                       ExtensionRuntimeEvidence evidence) {
        SamplerConfigurationContract.requireFinal(config);
        PropagatorConfigurationContract.requireSupported(config);

        ExtensionConfig extensionConfig = new ExtensionConfig(config);
        if (!extensionConfig.scrubbing().enabled()) {
            String message = "%s=false is forbidden by the secure Agent profile"
                    .formatted(ExtensionPropertyNames.SCRUBBING_ENABLED);

            throw new ConfigurationPropertyException(
                    ExtensionPropertyNames.SCRUBBING_ENABLED,
                    new ConfigurationException(message)
            );
        }

        TracingSettingsSource policy = TracingSettingsSource.fromStartup(extensionConfig, agentInstanceId);
        CompositeSampler core = new CompositeSampler(policy, categoryBOverrides(extensionConfig.sampling()));
        SafeSampler sampler = SafeSampler.protect(core);
        ScrubbingRuntime scrubbingRuntime = scrubberFactory.buildRuntime(extensionConfig);
        try {
            RemoteJmxMutationRegistrationAssessor remoteJmxMutationRegistrationAssessor = Objects.requireNonNull(
                    remoteJmxAssessorFactory.apply(config),
                    "Remote JMX assessor factory must not return null"
            );

            AutoconfigureSession prepared = new AutoconfigureSession(
                    agentInstanceId,
                    extensionConfig,
                    policy,
                    sampler,
                    evidence,
                    jmxRegistrar,
                    scrubbingRuntime,
                    remoteJmxMutationRegistrationAssessor
            );

            jmxRegistrar.setCompositeSampler(core);
            prepared.readiness().markInstalled(PlatformExtensionCapability.CONFIGURATION_LOADED);
            return prepared;
        } catch (RuntimeException | Error failure) {
            Throwable outcome = failure;
            try {
                scrubbingRuntime.close();
            } catch (RuntimeException | Error closeFailure) {
                outcome = BootstrapCleanupFailures.aggregate(outcome, closeFailure);
            }

            BootstrapCleanupFailures.rethrow(outcome);
            throw failure;
        }
    }

    private static SamplingPolicySnapshot categoryBOverrides(SamplingExtensionConfig sampling) {
        return SamplingPolicySnapshotFactory.create(
                sampling.dropPaths(),
                sampling.forceRecordValues()
        );
    }
}
