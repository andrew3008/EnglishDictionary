package space.br1440.platform.tracing.otel.javaagent.processor;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.api.spi.SpanAttributeScrubbingRule;
import space.br1440.platform.tracing.otel.javaagent.configuration.PlatformTracingDefaultsProvider;
import space.br1440.platform.tracing.otel.javaagent.processor.composite.PlatformCompositeSpanProcessor;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.rules.BuiltInSpanAttributeScrubbingRules;
import space.br1440.platform.tracing.runtime.propagation.RequestIdSupport;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SpanLimits;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

class RequestIdScrubbingSurvivalTest {

    private static final String SPAN_ATTRIBUTE_VALUE_LENGTH_LIMIT = "otel.span.attribute.value.length.limit";
    private static final AttributeKey<String> REQUEST_ID = AttributeKey.stringKey(PlatformAttributes.PLATFORM_REQUEST_ID);
    private static final AttributeKey<String> PASSWORD = AttributeKey.stringKey("db.password");
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-";

    @Test
    void canonicalRequestIdsSurviveBuiltInScrubbingWithinPlatformDefaultLimit() {
        // given
        int valueLengthLimit = platformSpanAttributeValueLengthLimit();
        assertThat(valueLengthLimit).isGreaterThanOrEqualTo(RequestIdSupport.MAX_LENGTH);
        String uuid = UUID.randomUUID().toString();
        String maxLength = "a".repeat(RequestIdSupport.MAX_LENGTH);
        SpanLimits limits = SpanLimits.builder()
                .setMaxAttributeValueLength(valueLengthLimit)
                .build();
        InMemorySpanExporter exporter = InMemorySpanExporter.create();

        // when
        List<SpanData> finished;
        try (SdkTracerProvider provider = SdkTracerProvider.builder()
                .setSpanLimits(limits)
                .addSpanProcessor(new PlatformCompositeSpanProcessor(List.of(
                        ScrubbingSpanProcessorTestFixtures.fromBuiltInRules(platformBuiltInRules()),
                        new SpanLimitsMetricsProcessor())))
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build()) {
            Tracer tracer = provider.get("request-id-scrubbing");
            export(tracer, "uuid-span", uuid);
            export(tracer, "max-span", maxLength);
            export(tracer, "alphabet-span", ALPHABET);
            finished = List.copyOf(exporter.getFinishedSpanItems());
        }

        // then
        assertThat(attribute(finished, "uuid-span")).isEqualTo(uuid);
        assertThat(attribute(finished, "max-span")).isEqualTo(maxLength);
        assertThat(attribute(finished, "alphabet-span")).isEqualTo(ALPHABET);
    }

    private static List<SpanAttributeScrubbingRule> platformBuiltInRules() {
        return BuiltInSpanAttributeScrubbingRules.defaultConfigNames().stream()
                .map(BuiltInSpanAttributeScrubbingRules::resolve)
                .toList();
    }

    private static int platformSpanAttributeValueLengthLimit() {
        String published = new PlatformTracingDefaultsProvider()
                .supply()
                .get(SPAN_ATTRIBUTE_VALUE_LENGTH_LIMIT);
        assertThat(published).isNotBlank();
        return Integer.parseInt(published);
    }

    private static void export(Tracer tracer, String spanName, String requestId) {
        Span span = tracer.spanBuilder(spanName).startSpan();
        span.setAttribute(REQUEST_ID, requestId);
        span.setAttribute(PASSWORD, "secret");
        span.end();
    }

    private static String attribute(List<SpanData> finished, String spanName) {
        SpanData span = finished.stream()
                .filter(item -> spanName.equals(item.getName()))
                .findFirst()
                .orElseThrow();
        assertThat(span.getAttributes().get(PASSWORD)).isEmpty();
        return span.getAttributes().get(REQUEST_ID);
    }
}
