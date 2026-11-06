package space.br1440.platform.tracing.otel.javaagent.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.api.propagation.PlatformBaggageKeys;
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
import io.opentelemetry.sdk.trace.ReadWriteSpan;

class BaggageSpanProcessorTest {

    private static final AttributeKey<String> TENANT = AttributeKey.stringKey("baggage.tenant-id");
    private static final AttributeKey<String> SECRET = AttributeKey.stringKey("baggage.api-secret");

    @RegisterExtension
    static OtelSdkExtension otel = OtelSdkExtension.builder()
            .scope(ScopeMode.METHOD)
            // policy в обход конфигурации, ключ резерва игнорируется процессором
            .addSpanProcessor(new BaggageSpanProcessor(new BaggageKeyPolicy(
                    Set.of("tenant-id", "tenant_class", "api-secret", "platform.correlation.id"),
                    Stream.of("secret", "password", "token")
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
    void copies_only_the_exact_allowlisted_key(
            OpenTelemetrySdk sdk, InMemorySpanExporter exporter) {
        // given
        Tracer tracer = sdk.getTracer("test");
        Context exact = Context.root().with(Baggage.builder().put("tenant_class", "silver").build());
        Context differentCase = Context.root().with(Baggage.builder().put("Tenant_Class", "gold").build());

        // when
        tracer.spanBuilder("exact").setParent(exact).startSpan().end();
        tracer.spanBuilder("different-case").setParent(differentCase).startSpan().end();

        // then
        assertThat(exporter.getFinishedSpanItems()).hasSize(2);
        assertThat(exporter.getFinishedSpanItems().getFirst().getAttributes().asMap())
                .containsExactlyEntriesOf(Attributes.of(AttributeKey.stringKey("baggage.tenant_class"), "silver").asMap());
        assertThat(exporter.getFinishedSpanItems().getLast().getAttributes().asMap()).isEmpty();
    }

    @Test
    void empty_policy_still_projects_canonical_correlation() {
        // given
        ReadWriteSpan span = mock(ReadWriteSpan.class);
        Context parent = baggage("platform.correlation.id", "workflow-42");

        // when
        try (BaggageSpanProcessor processor = new BaggageSpanProcessor(new BaggageKeyPolicy(Set.of(), List.of()))) {
            processor.onStart(parent, span);
        }

        // then
        verify(span).setAttribute(AttributeKey.stringKey(PlatformAttributes.PLATFORM_CORRELATION_ID), "workflow-42");
    }

    @Test
    void correlation_is_not_projected_when_value_or_key_is_not_canonical() {
        // given
        ReadWriteSpan blank = mock(ReadWriteSpan.class);
        ReadWriteSpan tooLong = mock(ReadWriteSpan.class);
        ReadWriteSpan differentCase = mock(ReadWriteSpan.class);

        // when
        try (BaggageSpanProcessor processor = new BaggageSpanProcessor(new BaggageKeyPolicy(Set.of(), List.of()))) {
            processor.onStart(baggage(PlatformBaggageKeys.CORRELATION_ID, ""), blank);
            processor.onStart(baggage(PlatformBaggageKeys.CORRELATION_ID, "a".repeat(129)), tooLong);
            processor.onStart(baggage("Platform.Correlation.Id", "workflow-42"), differentCase);
        }

        // then
        verifyNoInteractions(blank, tooLong, differentCase);
    }

    @Test
    void reserved_keys_are_not_copied_even_when_a_bypassed_policy_allows_them() {
        // given
        ReadWriteSpan span = mock(ReadWriteSpan.class);
        Context parent = Context.root().with(Baggage.builder()
                .put("platform.other", "x")
                .put(PlatformBaggageKeys.CORRELATION_ID, "workflow-42")
                .build());

        // when
        try (BaggageSpanProcessor processor = new BaggageSpanProcessor(new BaggageKeyPolicy(
                Set.of("platform.other", PlatformBaggageKeys.CORRELATION_ID), List.of()))) {
            processor.onStart(parent, span);
        }

        // then
        verify(span).setAllAttributes(Attributes.of(
                AttributeKey.stringKey(PlatformAttributes.PLATFORM_CORRELATION_ID), "workflow-42"));
    }

    @Test
    void does_not_touch_the_span_when_every_entry_is_filtered() {
        // given
        ReadWriteSpan span = mock(ReadWriteSpan.class);

        // when
        try (BaggageSpanProcessor processor = new BaggageSpanProcessor(
                new BaggageKeyPolicy(Set.of("tenant_class"), List.of()))) {
            processor.onStart(baggage("platform.other", "x"), span);
        }

        // then
        verifyNoInteractions(span);
    }

    @Test
    void empty_baggage_does_not_touch_the_span() {
        // given
        ReadWriteSpan span = mock(ReadWriteSpan.class);

        // when
        try (BaggageSpanProcessor processor =
                     new BaggageSpanProcessor(new BaggageKeyPolicy(Set.of("tenant_class"), List.of()))) {
            processor.onStart(Context.root(), span);
        }

        // then
        verifyNoInteractions(span);
    }

    private static Context baggage(String key, String value) {
        return Context.root().with(Baggage.builder().put(key, value).build());
    }
}
