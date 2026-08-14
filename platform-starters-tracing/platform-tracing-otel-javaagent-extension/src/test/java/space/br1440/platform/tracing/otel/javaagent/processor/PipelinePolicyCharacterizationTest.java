package space.br1440.platform.tracing.otel.javaagent.processor;

import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.otel.javaagent.processor.composite.PlatformCompositeSpanProcessor;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.rules.BuiltInSpanAttributeScrubbingRules;
import space.br1440.platform.tracing.test.assertions.EnrichmentAssertions;
import space.br1440.platform.tracing.test.assertions.ScrubbingAssertions;
import space.br1440.platform.tracing.test.assertions.ValidationAssertions;
import space.br1440.platform.tracing.test.harness.SpanProcessorHarness;
import io.opentelemetry.sdk.resources.Resource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Characterization of behavior that depends on relative processor ordering (PR-5B).
 * <p>
 * Actual factory composition is verified separately by
 * {@code PlatformSpanProcessorFactoryExtensionConfigAdoptionTest}.
 */
class PipelinePolicyCharacterizationTest {

    @Test
    void enriching_runs_before_scrubbing_in_composite_onEnding() {
        PlatformCompositeSpanProcessor composite = new PlatformCompositeSpanProcessor(List.of(
                new EnrichingSpanProcessor(),
                ScrubbingSpanProcessorTestFixtures.fromBuiltInRules(
                        List.of(BuiltInSpanAttributeScrubbingRules.resolve("oauth-header")))));

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
                ScrubbingSpanProcessorTestFixtures.fromBuiltInRules(
                        List.of(BuiltInSpanAttributeScrubbingRules.resolve("oauth-header"))),
                new ValidatingSpanProcessor(TracingSettingsSource.isolatedValidation(true, false, false))));

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
