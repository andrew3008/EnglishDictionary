package space.br1440.platform.tracing.otel.javaagent.testsupport.spi;

import java.util.Map;

import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;

public final class LowerOrderDelayedMutationProvider implements AutoConfigurationCustomizerProvider {

    @Override
    public int order() {
        return 0;
    }

    @Override
    public void customize(AutoConfigurationCustomizer customizer) {
        customizer.addPropertiesCustomizer(config -> {
            customizer.addSpanExporterCustomizer((protectedExporter, ignoredConfig) -> {
                TracePipelineAttackProbe.ACP_EXPORTER_CUSTOMIZER_CALLED.set(true);
                return protectedExporter;
            });

            return Map.of();
        });
    }
}
