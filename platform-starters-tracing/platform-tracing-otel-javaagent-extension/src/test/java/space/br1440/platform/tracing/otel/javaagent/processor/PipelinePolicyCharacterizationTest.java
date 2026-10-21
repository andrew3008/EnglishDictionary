package space.br1440.platform.tracing.otel.javaagent.processor;

import java.util.List;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSourceTestFixtures;
import space.br1440.platform.tracing.otel.javaagent.control.settings.validation.SpanAttributeValidationLevel;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.processor.composite.PlatformCompositeSpanProcessor;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.rules.BuiltInSpanAttributeScrubbingRules;
import space.br1440.platform.tracing.test.assertions.EnrichmentAssertions;
import space.br1440.platform.tracing.test.assertions.ScrubbingAssertions;
import space.br1440.platform.tracing.test.assertions.ValidationAssertions;
import space.br1440.platform.tracing.test.harness.SpanProcessorHarness;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.data.SpanData;

class PipelinePolicyCharacterizationTest {

    @Test
    void enriching_runs_before_scrubbing_in_composite_onEnding() {
        // given
        PlatformCompositeSpanProcessor composite = new PlatformCompositeSpanProcessor(List.of(
                new EnrichingSpanProcessor(),
                ScrubbingSpanProcessorTestFixtures.fromBuiltInRules(
                        List.of(BuiltInSpanAttributeScrubbingRules.OAUTH_HEADER.create()))));

        try (SpanProcessorHarness h = SpanProcessorHarness.of(composite)) {
            // when
            Tracer tracer = h.tracer("t");
            Span span = tracer.spanBuilder("server-op").setSpanKind(SpanKind.SERVER).startSpan();
            span.setAttribute("http.request.header.authorization", "Bearer secret");
            span.end();

            // then
            SpanData data = h.exporter().getFinishedSpanItems().getFirst();
            EnrichmentAssertions.assertPlatformType(data, "http_server");
            ScrubbingAssertions.assertStringAttributeEmpty(data, "http.request.header.authorization");
        }
    }

    @Test
    void validation_runs_before_enrichment_in_composite_onEnding() {
        // given
        PlatformCompositeSpanProcessor composite = new PlatformCompositeSpanProcessor(List.of(
                new ValidatingSpanProcessor(TracingSettingsSourceTestFixtures.boundValidation(AgentInstanceId.random(), SpanAttributeValidationLevel.WARNING)),
                new EnrichingSpanProcessor(),
                ScrubbingSpanProcessorTestFixtures.fromBuiltInRules(
                        List.of(BuiltInSpanAttributeScrubbingRules.OAUTH_HEADER.create())))
        );

        try (SpanProcessorHarness h = SpanProcessorHarness.of(composite, Resource.empty())) {
            // when
            Tracer tracer = h.tracer("t");
            Span span = tracer.spanBuilder("server-op").setSpanKind(SpanKind.SERVER).startSpan();
            span.setAttribute("http.request.header.authorization", "Bearer secret");
            span.end();

            // then
            SpanData data = h.exporter().getFinishedSpanItems().getFirst();
            ValidationAssertions.assertMissingRecorded(data, PlatformAttributes.PLATFORM_RESULT);
            EnrichmentAssertions.assertPlatformResult(data, "success");
            ScrubbingAssertions.assertStringAttributeEmpty(data, "http.request.header.authorization");
        }
    }

    @Test
    void validation_marker_absent_whenValidationDisabled() {
        // given
        PlatformCompositeSpanProcessor composite = new PlatformCompositeSpanProcessor(List.of(
                new ValidatingSpanProcessor(TracingSettingsSourceTestFixtures.boundValidation(AgentInstanceId.random(), SpanAttributeValidationLevel.DISABLED)),
                new EnrichingSpanProcessor(),
                ScrubbingSpanProcessorTestFixtures.fromBuiltInRules(
                        List.of(BuiltInSpanAttributeScrubbingRules.OAUTH_HEADER.create())))
        );

        try (SpanProcessorHarness h = SpanProcessorHarness.of(composite, Resource.empty())) {
            // when
            Tracer tracer = h.tracer("t");
            Span span = tracer.spanBuilder("server-op").setSpanKind(SpanKind.SERVER).startSpan();
            span.end();

            // then
            SpanData data = h.exporter().getFinishedSpanItems().getFirst();
            ValidationAssertions.assertMissingAbsent(data);
        }
    }
}
