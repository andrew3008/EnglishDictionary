package space.br1440.platform.tracing.test.junit;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.sdk.trace.samplers.SamplingResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import space.br1440.platform.tracing.test.junit.internal.ScopeMode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Проверяет правила Builder'а: обязательный {@code scope}, корректное применение
 * пользовательского sampler'а, доступность {@link Sampler} через ParameterResolver.
 */
class OtelSdkExtensionBuilderTest {

    @RegisterExtension
    static OtelSdkExtension otel = OtelSdkExtension.builder()
            .scope(ScopeMode.METHOD)
            .sampler(Sampler.alwaysOff())
            .build();

    @Test
    void builder_without_scope_throws_IllegalStateException() {
        assertThatThrownBy(() -> OtelSdkExtension.builder().build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("scope");
    }

    @Test
    void custom_sampler_is_applied(OpenTelemetrySdk sdk, InMemorySpanExporter exporter) {
        Tracer tracer = sdk.getTracer("test");
        Span span = tracer.spanBuilder("dropped").startSpan();
        span.end();

        // alwaysOff: span не должен попасть в exporter.
        assertThat(exporter.getFinishedSpanItems()).isEmpty();
    }

    @Test
    void sampler_is_available_via_ParameterResolver(Sampler sampler) {
        SamplingResult result = sampler.shouldSample(
                io.opentelemetry.context.Context.root(),
                "00000000000000000000000000000001",
                "any",
                io.opentelemetry.api.trace.SpanKind.INTERNAL,
                io.opentelemetry.api.common.Attributes.empty(),
                java.util.Collections.emptyList());
        // У alwaysOff — DROP.
        assertThat(result.getDecision().name()).isEqualTo("DROP");
    }
}
