package space.br1440.platform.tracing.otel.javaagent.factory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import space.br1440.platform.tracing.otel.javaagent.configuration.BaggageKeyPolicy;
import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionConfig;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;
import space.br1440.platform.tracing.otel.javaagent.jmx.registration.PlatformTracingMBeanRegistrar;
import space.br1440.platform.tracing.otel.javaagent.logging.PlatformAgentLogger;
import space.br1440.platform.tracing.otel.javaagent.logging.event.BootstrapLogEvent;
import space.br1440.platform.tracing.otel.javaagent.processor.BaggageSpanProcessor;
import space.br1440.platform.tracing.otel.javaagent.processor.ClassificationSpanProcessor;
import space.br1440.platform.tracing.otel.javaagent.processor.EnrichingSpanProcessor;
import space.br1440.platform.tracing.otel.javaagent.processor.RequestIdPropagatingSpanProcessor;
import space.br1440.platform.tracing.otel.javaagent.processor.ScrubbingSpanProcessor;
import space.br1440.platform.tracing.otel.javaagent.processor.SpanLimitsMetricsProcessor;
import space.br1440.platform.tracing.otel.javaagent.processor.ValidatingSpanProcessor;
import space.br1440.platform.tracing.otel.javaagent.processor.composite.PlatformCompositeSpanProcessor;
import space.br1440.platform.tracing.otel.javaagent.processor.spantimeout.SpanTimeoutProcessor;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionCapability;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime.ScrubbingRuntime;

import io.opentelemetry.sdk.trace.SdkTracerProviderBuilder;
import io.opentelemetry.sdk.trace.SpanProcessor;

public final class PlatformSpanProcessorFactory {

    private static final PlatformAgentLogger log = PlatformAgentLogger.get(PlatformSpanProcessorFactory.class);

    private final PlatformTracingMBeanRegistrar jmxRegistrar;
    private final ScrubbingRuntime scrubbingRuntime;

    public PlatformSpanProcessorFactory(PlatformTracingMBeanRegistrar jmxRegistrar, ScrubbingRuntime scrubbingRuntime) {
        this.jmxRegistrar = Objects.requireNonNull(jmxRegistrar, "jmxRegistrar must not be null");
        this.scrubbingRuntime = Objects.requireNonNull(scrubbingRuntime, "scrubbingRuntime must not be null");
    }

    public SdkTracerProviderBuilder registerSpanProcessors(SdkTracerProviderBuilder tpBuilder,
                                                           ExtensionConfig extConfig,
                                                           TracingSettingsSource acceptedPolicy) {
        Objects.requireNonNull(tpBuilder, "tpBuilder must not be null");
        Objects.requireNonNull(extConfig, "extConfig must not be null");
        Objects.requireNonNull(acceptedPolicy, "acceptedPolicy must not be null");

        try {
            List<SpanProcessor> delegates = buildDelegates(extConfig, acceptedPolicy);
            installComposite(tpBuilder, delegates);
            return tpBuilder;
        } catch (Throwable t) {
            log.log(BootstrapLogEvent.SPAN_PROCESSOR_REGISTRATION_FAILED, fields -> fields.cause(t));
            throw t;
        }
    }

    private List<SpanProcessor> buildDelegates(ExtensionConfig extConfig,
                                               TracingSettingsSource acceptedPolicy) {
        List<SpanProcessor> delegates = new ArrayList<>();
        baggageProcessor(extConfig).ifPresent(delegates::add);
        validatingProcessor(acceptedPolicy, delegates);
        delegates.add(new EnrichingSpanProcessor());
        delegates.add(new RequestIdPropagatingSpanProcessor());
        classificationProcessor(extConfig).ifPresent(delegates::add);
        spanTimeoutProcessor(extConfig).ifPresent(delegates::add);
        metricsProcessor(extConfig).ifPresent(delegates::add);
        scrubbingProcessor(extConfig).ifPresent(delegates::add);
        return delegates;
    }

    private Optional<SpanProcessor> baggageProcessor(ExtensionConfig extConfig) {
        BaggageKeyPolicy policy = extConfig.baggage().spanAttributesPolicy();
        if (policy.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(new BaggageSpanProcessor(policy));
    }

    private Optional<SpanProcessor> scrubbingProcessor(ExtensionConfig extConfig) {
        if (!extConfig.scrubbing().enabled()) {
            return Optional.empty();
        }

        ScrubbingSpanProcessor scrubbing = new ScrubbingSpanProcessor(scrubbingRuntime);
        jmxRegistrar.setScrubbing(scrubbingRuntime);
        scrubbingRuntime.acknowledgeProcessorInstalled();
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

    private Optional<SpanProcessor> spanTimeoutProcessor(ExtensionConfig extConfig) {
        if (!extConfig.spanTimeout().enabled()) {
            return Optional.empty();
        }

        SpanTimeoutProcessor spanTimeout = SpanTimeoutProcessor.start(
                extConfig.spanTimeout().maxSpanAge(),
                extConfig.spanTimeout().maxTraceAge(),
                extConfig.spanTimeout().scanInterval(),
                extConfig.spanTimeout().maxTrackedTraces(),
                extConfig.spanTimeout().maxTrackedSpansPerTrace(),
                extConfig.spanTimeout().maxTrackedSpans()
        );
        jmxRegistrar.setSpanTimeout(spanTimeout);
        return Optional.of(spanTimeout);
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
