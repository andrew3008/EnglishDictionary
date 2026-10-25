package space.br1440.platform.tracing.otel.javaagent.configuration;

import java.util.Objects;

import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import lombok.Getter;
import lombok.experimental.Accessors;

@Getter
@Accessors(fluent = true)
public final class ExtensionConfig {

    private final SamplingExtensionConfig sampling;
    private final ControlSettingsExtensionConfig controlSettings;
    private final ScrubbingExtensionConfig scrubbing;
    private final MetricsExtensionConfig metrics;
    private final ValidationExtensionConfig validation;
    private final ClassificationExtensionConfig classification;
    private final SpanTimeoutExtensionConfig spanTimeout;
    private final BaggageExtensionConfig baggage;
    private final ResourceExtensionConfig resource;

    public ExtensionConfig(ConfigProperties config) {
        Objects.requireNonNull(config, "config");

        ExtensionConfigReader reader = new ExtensionConfigReader(config);
        this.sampling = new SamplingExtensionConfig(reader);
        this.controlSettings = new ControlSettingsExtensionConfig(reader);
        this.scrubbing = new ScrubbingExtensionConfig(reader);
        this.metrics = new MetricsExtensionConfig(reader);
        this.validation = new ValidationExtensionConfig(reader);
        this.classification = new ClassificationExtensionConfig(reader);
        this.spanTimeout = new SpanTimeoutExtensionConfig(reader);
        this.baggage = new BaggageExtensionConfig(reader);
        this.resource = new ResourceExtensionConfig(reader);
    }
}
