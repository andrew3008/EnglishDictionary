package space.br1440.platform.tracing.otel.javaagent.processor;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.BuiltInSpanAttributeScrubbingRules;
import space.br1440.platform.tracing.test.assertions.EnrichmentAssertions;
import space.br1440.platform.tracing.test.assertions.ScrubbingAssertions;
import space.br1440.platform.tracing.test.assertions.ValidationAssertions;
import space.br1440.platform.tracing.test.harness.SpanProcessorHarness;
import io.opentelemetry.sdk.resources.Resource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Characterization relative processor ordering (PR-5B).
 * <p>
 * Observed factory order in {@code PlatformSpanProcessorFactory}: Baggage → Enriching → Scrubbing
 * → Validating → Classification → Watchdog → Metrics.
 */
class PipelinePolicyCharacterizationTest {

    private static final List<Class<?>> EXPECTED_FACTORY_PROCESSOR_ORDER = List.of(
            BaggageSpanProcessor.class,
            EnrichingSpanProcessor.class,
            ScrubbingSpanProcessor.class,
            ValidatingSpanProcessor.class,
            ClassificationSpanProcessor.class,
            SpanWatchdogProcessor.class,
            MetricsSpanProcessor.class
    );

    @Test
    void documented_factory_processor_order() {
        assertThat(EXPECTED_FACTORY_PROCESSOR_ORDER)
                .extracting(Class::getSimpleName)
                .containsExactly(
                        "BaggageSpanProcessor",
                        "EnrichingSpanProcessor",
                        "ScrubbingSpanProcessor",
                        "ValidatingSpanProcessor",
                        "ClassificationSpanProcessor",
                        "SpanWatchdogProcessor",
                        "MetricsSpanProcessor");
    }

    @Test
    void enriching_runs_before_scrubbing_in_composite_onEnding() {
        PlatformCompositeSpanProcessor composite = new PlatformCompositeSpanProcessor(List.of(
                new EnrichingSpanProcessor(),
                new ScrubbingSpanProcessor(List.of(BuiltInSpanAttributeScrubbingRules.resolve("oauth-header")))));

        try (SpanProcessorHarness h = SpanProcessorHarness.of(composite)) {
            Tracer tracer = h.tracer("t");
            Span span = tracer.spanBuilder("server-op").setSpanKind(SpanKind.SERVER).startSpan();
            span.setAttribute("http.request.header.authorization", "Bearer secret");
            span.end();

            var data = h.exporter().getFinishedSpanItems().get(0);
            EnrichmentAssertions.assertPlatformType(data, "http_server");
            ScrubbingAssertions.assertStringAttributeEmpty(data, "http.request.header.authorization");
        }
    }

    @Test
    void scrubbing_runs_before_validation_in_composite_onEnding() {
        PlatformCompositeSpanProcessor composite = new PlatformCompositeSpanProcessor(List.of(
                new EnrichingSpanProcessor(),
                new ScrubbingSpanProcessor(List.of(BuiltInSpanAttributeScrubbingRules.resolve("oauth-header"))),
                new ValidatingSpanProcessor(false)));

        try (SpanProcessorHarness h = SpanProcessorHarness.of(composite, Resource.empty())) {
            Tracer tracer = h.tracer("t");
            Span span = tracer.spanBuilder("server-op").setSpanKind(SpanKind.SERVER).startSpan();
            span.setAttribute("http.request.header.authorization", "Bearer secret");
            span.end();

            var data = h.exporter().getFinishedSpanItems().get(0);
            ScrubbingAssertions.assertStringAttributeEmpty(data, "http.request.header.authorization");
            ValidationAssertions.assertMissingAbsent(data);
            assertThat(data.getAttributes().get(AttributeKey.stringKey(PlatformAttributes.PLATFORM_TYPE)))
                    .isEqualTo("http_server");
        }
    }
}
