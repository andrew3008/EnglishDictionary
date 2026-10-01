package space.br1440.platform.tracing.otel.javaagent.propagation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionConfig;
import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionDefaults;
import space.br1440.platform.tracing.otel.javaagent.processor.BaggageSpanProcessor;
import space.br1440.platform.tracing.test.junit.OtelSdkExtension;
import space.br1440.platform.tracing.test.junit.internal.ScopeMode;

import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.autoconfigure.spi.internal.DefaultConfigProperties;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;

class BaggageIngressPipelineTest {

    private static final TextMapGetter<Map<String, String>> GETTER = new TextMapGetter<>() {

        @Override
        public Iterable<String> keys(Map<String, String> carrier) {
            return carrier.keySet();
        }

        @Override
        public String get(Map<String, String> carrier, String key) {
            return (carrier == null) ? null : carrier.get(key);
        }
    };

    @RegisterExtension
    static OtelSdkExtension otel = OtelSdkExtension.builder()
            .scope(ScopeMode.METHOD)
            .addSpanProcessor(new BaggageSpanProcessor(
                    new HashSet<>(ExtensionDefaults.DEFAULT_BAGGAGE_SPAN_ATTRIBUTES_ALLOWED_KEYS),
                    ExtensionDefaults.DEFAULT_BAGGAGE_SPAN_ATTRIBUTES_DENY_PATTERNS
            ))
            .build();

    @Test
    void ingress_does_not_project_spoofed_correlation_onto_span(OpenTelemetrySdk sdk,
                                                                InMemorySpanExporter exporter) {
        // given
        TextMapPropagator propagator = new BaggagePropagationCustomizer().apply(
                W3CBaggagePropagator.getInstance(),
                new ExtensionConfig(DefaultConfigProperties.createFromMap(Map.of())).baggage()
        );

        Context extracted = propagator.extract(
                Context.root(),
                Map.of("baggage", "platform.correlation.id=spoofed,tenant_class=gold"),
                GETTER
        );

        Tracer tracer = sdk.getTracer("baggage-ingress");

        // when
        try (Scope ignored = extracted.makeCurrent()) {
            Span span = tracer.spanBuilder("op").startSpan();
            span.end();
        }

        // then
        Attributes attributes = exporter.getFinishedSpanItems().getFirst().getAttributes();
        assertThat(attributes.get(AttributeKey.stringKey("baggage.tenant_class"))).isEqualTo("gold");
        assertThat(attributes.get(AttributeKey.stringKey(PlatformAttributes.PLATFORM_CORRELATION_ID))).isNull();
        assertThat(attributes.get(AttributeKey.stringKey("baggage.platform.correlation.id"))).isNull();
    }
}
