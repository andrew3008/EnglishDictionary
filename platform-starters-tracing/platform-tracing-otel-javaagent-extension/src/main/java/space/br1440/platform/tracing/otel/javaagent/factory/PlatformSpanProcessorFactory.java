package space.br1440.platform.tracing.otel.javaagent.factory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import jakarta.annotation.Nullable;

import space.br1440.platform.tracing.api.spi.SpanAttributeScrubbingRule;
import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionConfig;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;
import space.br1440.platform.tracing.otel.javaagent.jmx.registration.DomainMBeanRegistrar;
import space.br1440.platform.tracing.otel.javaagent.processor.BaggageSpanProcessor;
import space.br1440.platform.tracing.otel.javaagent.processor.ClassificationSpanProcessor;
import space.br1440.platform.tracing.otel.javaagent.processor.EnrichingSpanProcessor;
import space.br1440.platform.tracing.otel.javaagent.processor.ScrubbingSpanProcessor;
import space.br1440.platform.tracing.otel.javaagent.processor.SpanLimitsMetricsProcessor;
import space.br1440.platform.tracing.otel.javaagent.processor.SpanWatchdogProcessor;
import space.br1440.platform.tracing.otel.javaagent.processor.ValidatingSpanProcessor;
import space.br1440.platform.tracing.otel.javaagent.processor.composite.PlatformCompositeSpanProcessor;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionCapability;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.PlatformScrubberFactory;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.SanitizerBootstrapException;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.ScrubbingRuntime;

import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.trace.SdkTracerProviderBuilder;
import io.opentelemetry.sdk.trace.SpanProcessor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class PlatformSpanProcessorFactory {

    private final DomainMBeanRegistrar jmxRegistrar;
    private final PlatformScrubberFactory scrubberFactory;
    @Nullable
    private final ScrubbingRuntime sharedRuntime;

    PlatformSpanProcessorFactory(DomainMBeanRegistrar jmxRegistrar) {
        this(jmxRegistrar, new PlatformScrubberFactory(), null);
    }

    public PlatformSpanProcessorFactory(DomainMBeanRegistrar jmxRegistrar, ScrubbingRuntime sharedRuntime) {
        this(jmxRegistrar, new PlatformScrubberFactory(), Objects.requireNonNull(sharedRuntime, "sharedRuntime"));
    }

    PlatformSpanProcessorFactory(DomainMBeanRegistrar jmxRegistrar,
                                 Supplier<List<SpanAttributeScrubbingRule>> spiScrubbingRuleSource) {
        this(jmxRegistrar, new PlatformScrubberFactory(spiScrubbingRuleSource), null);
    }

    private PlatformSpanProcessorFactory(DomainMBeanRegistrar jmxRegistrar,
                                         PlatformScrubberFactory scrubberFactory,
                                         @Nullable ScrubbingRuntime sharedRuntime) {
        this.jmxRegistrar = Objects.requireNonNull(jmxRegistrar, "jmxRegistrar must not be null");
        this.scrubberFactory = Objects.requireNonNull(scrubberFactory, "scrubberFactory must not be null");
        this.sharedRuntime = sharedRuntime;
    }

    public SdkTracerProviderBuilder registerSpanProcessors(SdkTracerProviderBuilder tpBuilder,
                                                           ExtensionConfig extConfig,
                                                           ConfigProperties config,
                                                           TracingSettingsSource acceptedPolicy) {
        Objects.requireNonNull(tpBuilder, "tpBuilder must not be null");
        Objects.requireNonNull(extConfig, "extConfig must not be null");
        Objects.requireNonNull(config, "config must not be null");
        Objects.requireNonNull(acceptedPolicy, "acceptedPolicy must not be null");

        try {
            List<SpanProcessor> delegates = buildDelegates(extConfig, config, acceptedPolicy);
            installComposite(tpBuilder, delegates);
            return tpBuilder;
        } catch (Throwable t) {
            log.error("[platform-tracing] registerSpanProcessors failed", t);
            throw t;
        }
    }

    private List<SpanProcessor> buildDelegates(ExtensionConfig extConfig,
                                               ConfigProperties config,
                                               TracingSettingsSource acceptedPolicy) {
        List<SpanProcessor> delegates = new ArrayList<>();
        baggageProcessor(extConfig).ifPresent(delegates::add);
        enrichingProcessor(extConfig).ifPresent(delegates::add);
        validatingProcessor(acceptedPolicy, delegates);
        classificationProcessor(extConfig).ifPresent(delegates::add);
        watchdogProcessor(extConfig).ifPresent(delegates::add);
        metricsProcessor(extConfig).ifPresent(delegates::add);
        scrubbingProcessor(extConfig, config).ifPresent(delegates::add);
        return delegates;
    }

    private Optional<SpanProcessor> baggageProcessor(ExtensionConfig extConfig) {
        if (!extConfig.baggage().processorEnabled()) {
            return Optional.empty();
        }

        List<String> allowlist = extConfig.baggage().processorAllowedKeys();
        if (allowlist.isEmpty()) {
            return Optional.empty();
        }

        List<String> denyPatterns = extConfig.baggage().processorDenyPatterns();
        BaggageSpanProcessor processor = new BaggageSpanProcessor(
                new HashSet<>(allowlist),
                new ArrayList<>(denyPatterns)
        );
        return Optional.of(processor);
    }

    private Optional<SpanProcessor> enrichingProcessor(ExtensionConfig extConfig) {
        if (!extConfig.enriching().enabled()) {
            return Optional.empty();
        }

        EnrichingSpanProcessor processor = new EnrichingSpanProcessor(extConfig.enriching().remoteServicePriority());
        return Optional.of(processor);
    }

    private Optional<SpanProcessor> scrubbingProcessor(ExtensionConfig extConfig,
                                                       ConfigProperties config) {
        if (!extConfig.scrubbing().enabled()) {
            return Optional.empty();
        }

        ScrubbingRuntime runtime;
        if (sharedRuntime != null) {
            runtime = sharedRuntime;
        } else {
            try {
                runtime = scrubberFactory.buildRuntime(extConfig, config);
            } catch (SanitizerBootstrapException e) {
                jmxRegistrar.extensionReadiness().fail(e.code(), e);
                throw e;
            }
        }

        ScrubbingSpanProcessor scrubbing = new ScrubbingSpanProcessor(runtime);
        jmxRegistrar.setScrubbing(runtime);
        runtime.installation().acknowledgeProcessorInstalled();
        return Optional.of(scrubbing);
    }

    private void validatingProcessor(TracingSettingsSource acceptedPolicy,
                                     List<SpanProcessor> delegates) {
        ValidatingSpanProcessor validating = new ValidatingSpanProcessor(acceptedPolicy);
        jmxRegistrar.setValidating(validating);
        delegates.add(validating);
    }

    private Optional<SpanProcessor> classificationProcessor(ExtensionConfig extConfig) {
        if (!extConfig.classification().enabled()) {
            return Optional.empty();
        }

        return Optional.of(new ClassificationSpanProcessor(
                extConfig.classification().slowThreshold(),
                extConfig.classification().normalThreshold())
        );
    }

    private Optional<SpanProcessor> watchdogProcessor(ExtensionConfig extConfig) {
        if (!extConfig.watchdog().enabled()) {
            return Optional.empty();
        }

        SpanWatchdogProcessor watchdog = new SpanWatchdogProcessor(
                extConfig.watchdog().spanTimeout(),
                extConfig.watchdog().traceTimeout(),
                extConfig.watchdog().scanInterval()
        );
        jmxRegistrar.setWatchdog(watchdog);
        return Optional.of(watchdog);
    }

    private Optional<SpanProcessor> metricsProcessor(ExtensionConfig extConfig) {
        if (!extConfig.metrics().enabled()) {
            return Optional.empty();
        }

        SpanLimitsMetricsProcessor metricsProcessor = new SpanLimitsMetricsProcessor();
        jmxRegistrar.setMetrics(metricsProcessor);
        return Optional.of(metricsProcessor);
    }

    private void installComposite(SdkTracerProviderBuilder tpBuilder, List<SpanProcessor> delegates) {
        if (delegates.isEmpty()) {
            return;
        }

        PlatformCompositeSpanProcessor composite = new PlatformCompositeSpanProcessor(delegates);
        tpBuilder.addSpanProcessor(composite);
        jmxRegistrar.setComposite(composite);
        jmxRegistrar.extensionReadiness().markInstalled(PlatformExtensionCapability.REQUIRED_SPAN_PROCESSORS_INSTALLED);
    }
}
