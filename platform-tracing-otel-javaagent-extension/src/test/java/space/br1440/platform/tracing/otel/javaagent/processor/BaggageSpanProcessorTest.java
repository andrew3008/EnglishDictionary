package space.br1440.platform.tracing.otel.javaagent.processor;

import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import space.br1440.platform.tracing.test.junit.OtelSdkExtension;
import space.br1440.platform.tracing.test.junit.internal.ScopeMode;
import space.br1440.platform.tracing.api.attributes.PlatformAttributes;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class BaggageSpanProcessorTest {

    private static final AttributeKey<String> TENANT = AttributeKey.stringKey("baggage.tenant-id");
    private static final AttributeKey<String> SECRET = AttributeKey.stringKey("baggage.api-secret");

    @RegisterExtension
    static OtelSdkExtension otel = OtelSdkExtension.builder()
            .scope(ScopeMode.METHOD)
            .addSpanProcessor(new BaggageSpanProcessor(
                    Set.of("tenant-id", "platform.correlation.id"),
                    List.of("secret", "password", "token")
            ))
            .build();

    @Test
    void copies_allowlisted_baggage_to_span_attributes(OpenTelemetrySdk sdk, InMemorySpanExporter exporter) {
        Tracer tracer = sdk.getTracer("test");
        Context parent = Context.current().with(
                Baggage.builder()
                        .put("tenant-id", "billing")
                        .put("platform.correlation.id", "workflow-42")
                        .build()
        );

        try (var scope = parent.makeCurrent()) {
            Span span = tracer.spanBuilder("op").startSpan();
            span.end();
        }

        var attrs = exporter.getFinishedSpanItems().getFirst().getAttributes();
        assertThat(attrs.get(TENANT)).isEqualTo("billing");
        assertThat(attrs.get(AttributeKey.stringKey(PlatformAttributes.PLATFORM_CORRELATION_ID)))
                .isEqualTo("workflow-42");
        assertThat(attrs.get(AttributeKey.stringKey("baggage.platform.correlation.id"))).isNull();
    }

    @Test
    void deny_pattern_blocks_sensitive_keys_even_if_allowlisted(OpenTelemetrySdk sdk, InMemorySpanExporter exporter) {
        Tracer tracer = sdk.getTracer("test");
        Context parent = Context.current().with(
                Baggage.builder().put("api-secret", "shhh").build()
        );

        try (var scope = parent.makeCurrent()) {
            Span span = tracer.spanBuilder("op").startSpan();
            span.end();
        }

        assertThat(exporter.getFinishedSpanItems().getFirst().getAttributes().get(SECRET)).isNull();
    }

    @Test
    void non_allowlisted_keys_are_ignored(OpenTelemetrySdk sdk, InMemorySpanExporter exporter) {
        Tracer tracer = sdk.getTracer("test");
        Context parent = Context.current().with(
                Baggage.builder().put("user-id", "u-1").build()
        );

        try (var scope = parent.makeCurrent()) {
            Span span = tracer.spanBuilder("op").startSpan();
            span.end();
        }

        assertThat(exporter.getFinishedSpanItems().getFirst().getAttributes().asMap()).isEmpty();
    }
}
