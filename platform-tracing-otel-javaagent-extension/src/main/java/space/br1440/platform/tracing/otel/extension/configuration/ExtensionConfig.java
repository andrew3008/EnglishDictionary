package space.br1440.platform.tracing.otel.extension.configuration;

import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import lombok.Getter;
import lombok.experimental.Accessors;

import java.util.Objects;

@Getter
@Accessors(fluent = true)
public final class ExtensionConfig {

    private final SamplingExtensionConfig sampling;
    private final EnrichingExtensionConfig enriching;
    private final ScrubbingExtensionConfig scrubbing;
    private final MetricsExtensionConfig metrics;
    private final ValidationExtensionConfig validation;
    private final ResourceExtensionConfig resource;
    private final ClassificationExtensionConfig classification;
    private final WatchdogExtensionConfig watchdog;
    private final QueueExtensionConfig queue;
    private final BaggageExtensionConfig baggage;
    private final SdkExtensionConfig sdk;
    private final RuntimeControlExtensionConfig control;

    public ExtensionConfig(ConfigProperties config) {
        Objects.requireNonNull(config, "config");

        ExtensionConfigReader reader = new ExtensionConfigReader(config);
        this.sampling = new SamplingExtensionConfig(reader);
        this.enriching = new EnrichingExtensionConfig(reader);
        this.scrubbing = new ScrubbingExtensionConfig(reader);
        this.metrics = new MetricsExtensionConfig(reader);
        this.validation = new ValidationExtensionConfig(reader);
        this.resource = new ResourceExtensionConfig(reader);
        this.classification = new ClassificationExtensionConfig(reader);
        this.watchdog = new WatchdogExtensionConfig(reader);
        this.queue = new QueueExtensionConfig(reader);
        this.baggage = new BaggageExtensionConfig(reader);
        this.sdk = new SdkExtensionConfig(reader);
        this.control = new RuntimeControlExtensionConfig(reader);
    }
}
