package space.br1440.platform.tracing.e2e.fixture;

import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;

/** Test-only SPI fixture для проверки закрытия export path при bootstrap-ошибках. */
public final class MandatoryPipelineFailureExtension implements AutoConfigurationCustomizerProvider {

    private static final String FAILURE_STAGE = "platform.tracing.e2.failure-stage";

    @Override
    public void customize(AutoConfigurationCustomizer customizer) {
        String stage = System.getProperty(FAILURE_STAGE, "");
        if ("extension-initialization".equals(stage)) {
            throw failure(stage);
        }
        if ("configuration".equals(stage) || "sanitizer".equals(stage)) {
            customizer.addPropertiesCustomizer(config -> {
                throw failure(stage);
            });
        }
        if ("sampler".equals(stage)) {
            customizer.addSamplerCustomizer((sampler, config) -> {
                throw failure(stage);
            });
        }
        if ("span-processor".equals(stage)) {
            customizer.addTracerProviderCustomizer((builder, config) -> {
                throw failure(stage);
            });
        }
        if ("propagation".equals(stage)) {
            customizer.addPropagatorCustomizer((propagator, config) -> {
                throw failure(stage);
            });
        }
        if ("exporter".equals(stage) || "protected-export-path".equals(stage)) {
            customizer.addSpanExporterCustomizer((exporter, config) -> {
                throw failure(stage);
            });
        }
    }

    private static IllegalStateException failure(String stage) {
        return new IllegalStateException("E2_TEST_ONLY_MANDATORY_PIPELINE_FAILURE:" + stage);
    }
}
