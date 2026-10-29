package space.br1440.platform.tracing.otel.javaagent.processor;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.otel.javaagent.configuration.BaggageKeyPolicy;
import space.br1440.platform.tracing.test.junit.OtelSdkExtension;
import space.br1440.platform.tracing.test.junit.internal.ScopeMode;

import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;

class BaggageSpanProcessorTest {

    private static final AttributeKey<String> TENANT = AttributeKey.stringKey("baggage.tenant-id");
    private static final AttributeKey<String> SECRET = AttributeKey.stringKey("baggage.api-secret");

    @RegisterExtension
    static OtelSdkExtension otel = OtelSdkExtension.builder()
            .scope(ScopeMode.METHOD)
            .addSpanProcessor(new BaggageSpanProcessor(new BaggageKeyPolicy(
                    Set.of("tenant-id", "tenant_class", "api-secret", "platform.correlation.id"),
                    List.of("secret", "password", "token").stream()
                            .map(pattern -> Pattern.compile(pattern, Pattern.CASE_INSENSITIVE))
                            .toList()
            )))
            .build();

    @Test
    void copies_allowlisted_baggage_to_span_attributes(OpenTelemetrySdk sdk, InMemorySpanExporter exporter) {
        // given
        Tracer tracer = sdk.getTracer("test");
        Context parent = Context.current().with(
                Baggage.builder()
                        .put("tenant-id", "billing")
                        .put("platform.correlation.id", "workflow-42")
                        .build()
        );

        // when
        try (Scope ignored = parent.makeCurrent()) {
            Span span = tracer.spanBuilder("op").startSpan();
            span.end();
        }

        // then
        Attributes attrs = exporter.getFinishedSpanItems().getFirst().getAttributes();
        assertThat(attrs.get(TENANT)).isEqualTo("billing");
        assertThat(attrs.get(AttributeKey.stringKey(PlatformAttributes.PLATFORM_CORRELATION_ID)))
                .isEqualTo("workflow-42");
        assertThat(attrs.get(AttributeKey.stringKey("baggage.platform.correlation.id"))).isNull();
    }

    @Test
    void deny_pattern_blocks_sensitive_keys_even_if_allowlisted(OpenTelemetrySdk sdk, InMemorySpanExporter exporter) {
        // given
        Tracer tracer = sdk.getTracer("test");
        Context parent = Context.current().with(
                Baggage.builder().put("api-secret", "shhh").build()
        );

        // when
        try (Scope ignored = parent.makeCurrent()) {
            Span span = tracer.spanBuilder("op").startSpan();
            span.end();
        }

        // then
        assertThat(exporter.getFinishedSpanItems().getFirst().getAttributes().get(SECRET)).isNull();
    }

    @Test
    void non_allowlisted_keys_are_ignored(OpenTelemetrySdk sdk, InMemorySpanExporter exporter) {
        // given
        Tracer tracer = sdk.getTracer("test");
        Context parent = Context.current().with(
                Baggage.builder().put("user-id", "u-1").build()
        );

        // when
        try (Scope ignored = parent.makeCurrent()) {
            Span span = tracer.spanBuilder("op").startSpan();
            span.end();
        }

        // then
        assertThat(exporter.getFinishedSpanItems().getFirst().getAttributes().asMap()).isEmpty();
    }

    @Test
    void attribute_name_uses_normalized_key_regardless_of_first_seen_case(
            OpenTelemetrySdk sdk, InMemorySpanExporter exporter) {
        // given
        Tracer tracer = sdk.getTracer("test");
        Context mixedCase = Context.root().with(Baggage.builder().put("Tenant_Class", "gold").build());
        Context lowerCase = Context.root().with(Baggage.builder().put("tenant_class", "silver").build());

        // when
        tracer.spanBuilder("mixed-case").setParent(mixedCase).startSpan().end();
        tracer.spanBuilder("lower-case").setParent(lowerCase).startSpan().end();

        // then
        assertThat(exporter.getFinishedSpanItems()).hasSize(2);
        assertThat(exporter.getFinishedSpanItems().getFirst().getAttributes().asMap())
                .containsExactlyEntriesOf(Attributes.of(AttributeKey.stringKey("baggage.tenant_class"), "gold").asMap());
        assertThat(exporter.getFinishedSpanItems().getLast().getAttributes().asMap())
                .containsExactlyEntriesOf(Attributes.of(AttributeKey.stringKey("baggage.tenant_class"), "silver").asMap());
    }
}
