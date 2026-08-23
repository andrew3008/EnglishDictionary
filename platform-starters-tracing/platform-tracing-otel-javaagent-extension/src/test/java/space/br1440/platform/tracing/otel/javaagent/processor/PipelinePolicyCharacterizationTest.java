package space.br1440.platform.tracing.otel.javaagent.processor;

import java.util.List;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;
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

            var data = h.exporter().getFinishedSpanItems().getFirst();
            EnrichmentAssertions.assertPlatformType(data, "http_server");
            ScrubbingAssertions.assertStringAttributeEmpty(data, "http.request.header.authorization");
        }
    }

    @Test
    void validation_runs_before_enrichment_in_composite_onEnding() {
        // given: composite в целевом порядке validation, enrichment, scrubbing
        PlatformCompositeSpanProcessor composite = new PlatformCompositeSpanProcessor(List.of(
                new ValidatingSpanProcessor(TracingSettingsSource.isolatedValidation(true, false, false)),
                new EnrichingSpanProcessor(),
                ScrubbingSpanProcessorTestFixtures.fromBuiltInRules(
                        List.of(BuiltInSpanAttributeScrubbingRules.resolve("oauth-header")))));

        try (SpanProcessorHarness h = SpanProcessorHarness.of(composite, Resource.empty())) {
            // when: span завершается без producer-объявленного platform.trace.result
            Tracer tracer = h.tracer("t");
            Span span = tracer.spanBuilder("server-op").setSpanKind(SpanKind.SERVER).startSpan();
            span.setAttribute("http.request.header.authorization", "Bearer secret");
            span.end();

            // then: validation видит span раньше enrichment — platform.trace.type уже
            // проставлен start-фазой enrichment, а platform.trace.result ещё не проставлен
            var data = h.exporter().getFinishedSpanItems().getFirst();
            ValidationAssertions.assertMissingRecorded(data, PlatformAttributes.PLATFORM_RESULT);
            // enrichment остаётся fallback-нормализатором и всё равно проставляет итоговый result
            EnrichmentAssertions.assertPlatformResult(data, "success");
            ScrubbingAssertions.assertStringAttributeEmpty(data, "http.request.header.authorization");
        }
    }

    @Test
    void validation_marker_absent_whenValidationDisabled() {
        // given: тот же порядок, но validation отключена в политике
        PlatformCompositeSpanProcessor composite = new PlatformCompositeSpanProcessor(List.of(
                new ValidatingSpanProcessor(TracingSettingsSource.isolatedValidation(false, false, false)),
                new EnrichingSpanProcessor(),
                ScrubbingSpanProcessorTestFixtures.fromBuiltInRules(
                        List.of(BuiltInSpanAttributeScrubbingRules.resolve("oauth-header")))));

        try (SpanProcessorHarness h = SpanProcessorHarness.of(composite, Resource.empty())) {
            // when: span завершается без producer-объявленного result
            Tracer tracer = h.tracer("t");
            Span span = tracer.spanBuilder("server-op").setSpanKind(SpanKind.SERVER).startSpan();
            span.end();

            // then: маркер не появляется — validation не участвует в pipeline
            var data = h.exporter().getFinishedSpanItems().get(0);
            ValidationAssertions.assertMissingAbsent(data);
        }
    }
}
