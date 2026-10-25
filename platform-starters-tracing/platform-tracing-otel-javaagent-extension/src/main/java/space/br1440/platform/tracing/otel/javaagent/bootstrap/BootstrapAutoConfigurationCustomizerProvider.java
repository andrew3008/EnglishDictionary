package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import java.util.Objects;

import space.br1440.platform.tracing.otel.javaagent.bootstrap.session.AutoconfigureSession;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.session.SamplerConfigurationContract;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.verification.TracePipelineSpiVerifier;
import space.br1440.platform.tracing.otel.javaagent.configuration.PlatformTracingDefaultsProvider;
import space.br1440.platform.tracing.otel.javaagent.configuration.ResourceValidationMode;
import space.br1440.platform.tracing.otel.javaagent.exporter.response.ObservingSenderPreference;
import space.br1440.platform.tracing.otel.javaagent.propagation.BaggagePropagationCustomizer;
import space.br1440.platform.tracing.otel.javaagent.propagation.PlatformPropagatorsDefaultsCustomizer;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionCapability;
import space.br1440.platform.tracing.otel.javaagent.resource.PlatformResourceCustomizer;

import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;
import io.opentelemetry.sdk.resources.Resource;

public final class BootstrapAutoConfigurationCustomizerProvider implements AutoConfigurationCustomizerProvider {

    private static final int PLATFORM_EXTENSION_ORDER = 100;
    private static final String X_TRACE_ON = "X-Trace-On";

    private final BootstrapCoordinator bootstrap;
    private final PlatformTracingDefaultsProvider defaultsProvider = new PlatformTracingDefaultsProvider();
    private final BaggagePropagationCustomizer baggageCustomizer = new BaggagePropagationCustomizer();
    private final PlatformPropagatorsDefaultsCustomizer propagatorsDefaults = new PlatformPropagatorsDefaultsCustomizer();
    private final PlatformResourceCustomizer resourceCustomizer = new PlatformResourceCustomizer();

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
                TracePipelineSpiVerifier.verify(PLATFORM_EXTENSION_ORDER, getClass());
                return null;
            });

            return SamplerConfigurationContract.normalize(config);
        });

        customizer.addPropertiesCustomizer(propagatorsDefaults);

        customizer.addResourceCustomizer((existing, config) -> {
            bootstrap.prepare(config);
            ResourceValidationMode validationMode = bootstrap.session().extensionConfig().resource().validationMode();
            Resource customized = bootstrap.execute(
                    "RESOURCE_INITIALIZATION_FAILED",
                    () -> resourceCustomizer.customize(existing, config, validationMode)
            );

            bootstrap.execute("SENDER_SPI_INSTALLATION_FAILED", () -> {
                ObservingSenderPreference.install();
                return null;
            });

            return customized;
        });

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

        customizer.addTracerProviderCustomizer((builder, config) ->
                bootstrap.execute("SPAN_PROCESSOR_INITIALIZATION_FAILED", () -> {
                    AutoconfigureSession session = bootstrap.session();
                    builder.setSampler(session.sampler());
                    return session.spanProcessorFactory().registerSpanProcessors(
                            builder, session.extensionConfig(), session.policy()
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
                            processor, config
                    );
                }));
    }
}
