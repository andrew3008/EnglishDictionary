package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import java.util.Map;
import java.util.Objects;

import space.br1440.platform.tracing.otel.javaagent.configuration.PlatformTracingDefaultsProvider;
import space.br1440.platform.tracing.otel.javaagent.propagation.BaggagePropagationCustomizer;
import space.br1440.platform.tracing.otel.javaagent.propagation.PlatformPropagatorsDefaultsCustomizer;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionCapability;
import space.br1440.platform.tracing.otel.javaagent.resource.SafeResourceCustomizer;
import space.br1440.platform.tracing.otel.javaagent.sampler.PlatformManagedSamplers;

import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;
import io.opentelemetry.sdk.trace.samplers.Sampler;

public final class BootstrapAutoConfigurationCustomizerProvider implements AutoConfigurationCustomizerProvider {

    private static final int PLATFORM_EXTENSION_ORDER = 100;
    private static final String X_TRACE_ON = "X-Trace-On";

    private final BootstrapCoordinator bootstrap;
    private final PlatformTracingDefaultsProvider defaultsProvider = new PlatformTracingDefaultsProvider();
    private final BaggagePropagationCustomizer baggageCustomizer = new BaggagePropagationCustomizer();
    private final PlatformPropagatorsDefaultsCustomizer propagatorsDefaults = new PlatformPropagatorsDefaultsCustomizer();
    private final SafeResourceCustomizer resourceCustomizer = new SafeResourceCustomizer();

    @SuppressWarnings("unused")
    public BootstrapAutoConfigurationCustomizerProvider() {
        this(ExtensionRuntime.bootstrap());
    }

    BootstrapAutoConfigurationCustomizerProvider(BootstrapCoordinator bootstrap) {
        this.bootstrap = Objects.requireNonNull(bootstrap, "bootstrap");
    }

    @Override
    public int order() {
        return PLATFORM_EXTENSION_ORDER;
    }

    @Override
    public void customize(AutoConfigurationCustomizer customizer) {
        customizer.addPropertiesSupplier(defaultsProvider::supply);

        customizer.addPropertiesCustomizer(config -> {
            bootstrap.execute("TRACE_PIPELINE_ATTESTATION_FAILED", () -> {
                TracePipelineSpiAttestation.attest(PLATFORM_EXTENSION_ORDER, getClass());
                return null;
            });
            bootstrap.prepare(config);
            return Map.of();
        });

        customizer.addPropertiesCustomizer(propagatorsDefaults);

        customizer.addResourceCustomizer((existing, config) ->
                bootstrap.execute("RESOURCE_INITIALIZATION_FAILED",
                        () -> resourceCustomizer.customize(existing, config)));

        customizer.addPropagatorCustomizer((propagator, config) ->
                bootstrap.execute("PROPAGATION_INITIALIZATION_FAILED", () -> {
                    AutoconfigureSession session = bootstrap.session();
                    TextMapPropagator customized = baggageCustomizer.apply(propagator, session.extensionConfig().baggage());

                    boolean platformControlVisible = customized.fields().stream()
                            .anyMatch(X_TRACE_ON::equalsIgnoreCase);
                    if (platformControlVisible) {
                        session.readiness().markInstalled(PlatformExtensionCapability.PROPAGATION_HOOKS_INSTALLED);
                    }

                    return customized;
                }));

        customizer.addSamplerCustomizer((existing, config) ->
                bootstrap.execute("SAMPLER_INITIALIZATION_FAILED", () -> {
                    AutoconfigureSession session = bootstrap.session();
                    Sampler sampler = session.samplerFactory().buildSampler(
                            existing, session.extensionConfig().sampling(), session.policy()
                    );

                    if (PlatformManagedSamplers.isPlatformManaged(sampler)) {
                        session.readiness().markInstalled(PlatformExtensionCapability.PLATFORM_SAMPLER_INSTALLED);
                    }

                    return sampler;
                }));

        customizer.addTracerProviderCustomizer((builder, config) ->
                bootstrap.execute("SPAN_PROCESSOR_INITIALIZATION_FAILED", () -> {
                    AutoconfigureSession session = bootstrap.session();
                    return session.spanProcessorFactory().registerSpanProcessors(
                            builder, session.extensionConfig(), config, session.policy()
                    );
                }));

        customizer.addSpanExporterCustomizer((exporter, config) ->
                bootstrap.execute("EXPORTER_INITIALIZATION_FAILED", () -> {
                    AutoconfigureSession session = bootstrap.session();
                    return session.exportProcessorFactory().captureExporter(exporter, session.policy());
                }));

        customizer.addSpanProcessorCustomizer((processor, config) ->
                bootstrap.execute("EXPORT_PROCESSOR_INITIALIZATION_FAILED", () -> {
                    AutoconfigureSession session = bootstrap.session();
                    return session.exportProcessorFactory().maybeReplaceExportProcessor(
                            processor, session.extensionConfig().queue(), config
                    );
                }));
    }
}
