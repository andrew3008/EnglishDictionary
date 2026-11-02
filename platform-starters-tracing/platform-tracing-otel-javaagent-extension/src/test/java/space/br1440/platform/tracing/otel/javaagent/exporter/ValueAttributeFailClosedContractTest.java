package space.br1440.platform.tracing.otel.javaagent.exporter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import space.br1440.platform.tracing.api.spi.ScrubbingDecision;
import space.br1440.platform.tracing.api.spi.SpanAttributeScrubbingRule;
import space.br1440.platform.tracing.otel.javaagent.configuration.OtelSdkDefaults;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSourceTestFixtures;
import space.br1440.platform.tracing.otel.javaagent.exporter.boundary.BoundaryTestSupport;
import space.br1440.platform.tracing.otel.javaagent.exporter.guard.OtlpRequestSizeLimitingSpanExporter;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.processor.ScrubbingSpanProcessor;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime.ScrubbingRuntime;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.KeyValue;
import io.opentelemetry.api.common.Value;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;

class ValueAttributeFailClosedContractTest {

    private static final String CANARY = "supersecret-canary";
    private static final String PROBE_KEY = "payload";
    private static final AttributeKey<Value<?>> KEY = AttributeKey.valueKey(PROBE_KEY);

    static Stream<Arguments> valueShapes() {
        return Stream.of(
                Arguments.of("scalar-string", Value.of(CANARY)),
                Arguments.of("scalar-long", Value.of(42L)),
                Arguments.of("scalar-double", Value.of(1.5d)),
                Arguments.of("scalar-boolean", Value.of(true)),
                Arguments.of("bytes", Value.of(CANARY.getBytes(StandardCharsets.UTF_8))),
                Arguments.of("empty", Value.empty()),
                Arguments.of("string-array", Value.of(Value.of(CANARY), Value.of("second"))),
                Arguments.of("long-array", Value.of(Value.of(7L), Value.of(8L))),
                Arguments.of("heterogeneous-array", Value.of(Value.of(CANARY), Value.of(7L))),
                Arguments.of("kv-list", Value.of(Map.of("password", Value.of(CANARY)))),
                Arguments.of("nested", Value.of(KeyValue.of("outer", Value.of(
                        Value.of(Map.of("inner", Value.of(CANARY))),
                        Value.of(new byte[]{1, 2, 3})))))
        );
    }

    @ParameterizedTest(name = "VALUE не переживает exporter boundary: {0}")
    @MethodSource("valueShapes")
    void valueNeverSurvivesTheExportBoundary(String caseName, Value<?> payload) {
        // given
        InMemorySpanExporter delegate = InMemorySpanExporter.create();
        SpanAttributeScrubbingRule rule = mock(SpanAttributeScrubbingRule.class);
        when(rule.name()).thenReturn("test-rule");
        when(rule.evaluate(anyString(), any())).thenReturn(ScrubbingDecision.keep());
        ScrubbingRuntime runtime = BoundaryTestSupport.runtimeOf(List.of(rule));
        SpanExporter exporter = new SafeSpanExporter(
                new OtlpRequestSizeLimitingSpanExporter(delegate, Math.toIntExact(OtelSdkDefaults.MAX_OTLP_REQUEST_SIZE_BYTES)),
                TracingSettingsSourceTestFixtures.boundDefaults(AgentInstanceId.random()), runtime);

        // when
        List<SpanData> finished;
        try (runtime; SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(new ScrubbingSpanProcessor(runtime))
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build()) {
            Span span = provider.get("value-contract").spanBuilder("op").startSpan();
            span.setAttribute(KEY, payload);
            span.setAttribute(AttributeKey.stringKey("control.marker"), "present");
            span.end();

            finished = List.copyOf(delegate.getFinishedSpanItems());
        }

        // then
        verify(rule, never()).evaluate(eq(PROBE_KEY), any());
        verify(rule).evaluate("control.marker", "present");
        assertThat(finished).hasSize(1);
        SpanData exported = finished.getFirst();

        assertThat(exported.getAttributes().asMap().keySet())
                .as(caseName + ": контракт VALUE_* — отсутствие semantic key целиком, включая alias от процессора")
                .noneMatch(key -> PROBE_KEY.equals(key.getKey()));
        assertThat(renderedPayload(exported))
                .as("ни одно представление не должно донести канарейку до экспорта")
                .doesNotContain(CANARY);
        assertThat(exported.getAttributes().get(AttributeKey.stringKey("control.marker")))
                .as("отрицательная проверка не должна проходить vacuously")
                .isEqualTo("present");
        assertThat(runtime.boundaryMetrics().valueAttributesRemoved())
                .as("удаление обязано быть наблюдаемым для оператора")
                .isPositive();
        assertThat(runtime.attributesVerified()).isTrue();
        assertThat(runtime.failures()).isZero();
    }

    @Test
    void coerced_value_is_removed_at_export_when_no_rules_are_configured() {
        // given
        InMemorySpanExporter delegate = InMemorySpanExporter.create();
        ScrubbingRuntime runtime = BoundaryTestSupport.runtimeOf(List.of());
        SpanExporter exporter = new SafeSpanExporter(
                new OtlpRequestSizeLimitingSpanExporter(delegate, Math.toIntExact(OtelSdkDefaults.MAX_OTLP_REQUEST_SIZE_BYTES)),
                TracingSettingsSourceTestFixtures.boundDefaults(AgentInstanceId.random()), runtime);

        // when
        List<SpanData> finished;
        try (runtime; SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(new ScrubbingSpanProcessor(runtime))
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build()) {
            Span span = provider.get("value-contract").spanBuilder("op").startSpan();
            span.setAttribute(KEY, Value.of(CANARY));
            span.setAttribute(AttributeKey.stringKey("control.marker"), "present");
            span.end();

            finished = List.copyOf(delegate.getFinishedSpanItems());
        }

        // then
        assertThat(finished).hasSize(1);
        SpanData exported = finished.getFirst();
        assertThat(exported.getAttributes().asMap().keySet())
                .noneMatch(key -> PROBE_KEY.equals(key.getKey()));
        assertThat(renderedPayload(exported)).doesNotContain(CANARY);
        assertThat(exported.getAttributes().get(AttributeKey.stringKey("control.marker")))
                .isEqualTo("present");
        assertThat(runtime.attributesVerified()).isTrue();
        assertThat(runtime.boundaryMetrics().valueAttributesRemoved()).isPositive();
    }

    private static String renderedPayload(SpanData spanData) {
        List<String> parts = new ArrayList<>();
        spanData.getAttributes().forEach((key, value) -> {
            parts.add(key.getKey());
            parts.add(value instanceof Value<?> typed ? typed.asString() : String.valueOf(value));
        });
        return String.join("|", parts);
    }
}
