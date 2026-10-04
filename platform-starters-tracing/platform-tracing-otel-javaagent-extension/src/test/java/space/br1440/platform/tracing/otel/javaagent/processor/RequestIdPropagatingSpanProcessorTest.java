package space.br1440.platform.tracing.otel.javaagent.processor;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import jakarta.annotation.Nonnull;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.test.junit.OtelSdkExtension;
import space.br1440.platform.tracing.test.junit.internal.ScopeMode;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;

class RequestIdPropagatingSpanProcessorTest {

    private static final AttributeKey<String> REQUEST_ID = AttributeKey.stringKey(PlatformAttributes.PLATFORM_REQUEST_ID);
    private static final String REMOTE_TRACE_ID = "0123456789abcdef0123456789abcdef";
    private static final String REMOTE_SPAN_ID = "0123456789abcdef";

    @RegisterExtension
    static OtelSdkExtension otel = OtelSdkExtension.builder()
            .scope(ScopeMode.METHOD)
            .addSpanProcessor(new PresetChildAttributeProcessor())
            .addSpanProcessor(new RequestIdPropagatingSpanProcessor())
            .build();

    @Test
    void copiesRequestIdFromParentThroughGrandchild(OpenTelemetrySdk sdk, InMemorySpanExporter exporter) {
        // given
        Tracer tracer = sdk.getTracer("request-id-propagation");
        Span parent = tracer.spanBuilder("root").startSpan();
        parent.setAttribute(REQUEST_ID, "req-root");

        // when
        Span child;
        Span grandchild;
        try (Scope ignored = parent.makeCurrent()) {
            child = tracer.spanBuilder("child").startSpan();
            try (Scope ignored1 = child.makeCurrent()) {
                grandchild = tracer.spanBuilder("grandchild").startSpan();
            }
        }
        grandchild.end();
        child.end();
        parent.end();

        // then
        assertThat(attribute(exporter, "child")).isEqualTo("req-root");
        assertThat(attribute(exporter, "grandchild")).isEqualTo("req-root");
        assertThat(attribute(exporter, "root")).isEqualTo("req-root");
    }

    @Test
    void usesExplicitParentContextInsteadOfCurrentSpan(OpenTelemetrySdk sdk, InMemorySpanExporter exporter) {
        // given
        Tracer tracer = sdk.getTracer("request-id-propagation");
        Span ambient = tracer.spanBuilder("ambient").startSpan();
        ambient.setAttribute(REQUEST_ID, "ambient-id");
        Span explicitParent = tracer.spanBuilder("explicit-parent").startSpan();
        explicitParent.setAttribute(REQUEST_ID, "explicit-id");

        // when
        Span child;
        try (Scope ignored = ambient.makeCurrent()) {
            child = tracer.spanBuilder("explicit-child")
                    .setParent(Context.root().with(explicitParent))
                    .startSpan();
        }
        child.end();
        explicitParent.end();
        ambient.end();

        // then
        assertThat(attribute(exporter, "explicit-child")).isEqualTo("explicit-id");
    }

    @Test
    void preservesEmptyRequestIdAlreadySetOnChild(OpenTelemetrySdk sdk, InMemorySpanExporter exporter) {
        // given
        Tracer tracer = sdk.getTracer("request-id-propagation");
        Span parent = tracer.spanBuilder("sealed-parent").startSpan();
        parent.setAttribute(REQUEST_ID, "parent-id");

        // when
        Span child;
        try (Scope ignored = parent.makeCurrent()) {
            child = tracer.spanBuilder("sealed-child").startSpan();
        }
        child.end();
        parent.end();

        // then
        assertThat(attribute(exporter, "sealed-child")).isEmpty();
    }

    @Test
    void preservesNonEmptyRequestIdAlreadySetOnChild(OpenTelemetrySdk sdk, InMemorySpanExporter exporter) {
        // given
        Tracer tracer = sdk.getTracer("request-id-propagation");
        Span parent = tracer.spanBuilder("kept-parent").startSpan();
        parent.setAttribute(REQUEST_ID, "parent-id");

        // when
        Span child;
        try (Scope ignored = parent.makeCurrent()) {
            child = tracer.spanBuilder("kept-child").startSpan();
        }
        child.end();
        parent.end();

        // then
        assertThat(attribute(exporter, "kept-child")).isEqualTo("keep-me");
    }

    @Test
    void doesNotCopyFromRemoteParent(OpenTelemetrySdk sdk, InMemorySpanExporter exporter) {
        // given
        Tracer tracer = sdk.getTracer("request-id-propagation");
        SpanContext remote = SpanContext.createFromRemoteParent(
                REMOTE_TRACE_ID,
                REMOTE_SPAN_ID,
                TraceFlags.getSampled(),
                TraceState.getDefault());

        // when
        Span child = tracer.spanBuilder("remote-child")
                .setParent(Context.root().with(Span.wrap(remote)))
                .startSpan();
        child.end();

        // then
        assertThat(attribute(exporter, "remote-child")).isNull();
    }

    @Test
    void doesNotCopyWhenParentHasNoRequestId(OpenTelemetrySdk sdk, InMemorySpanExporter exporter) {
        // given
        Tracer tracer = sdk.getTracer("request-id-propagation");
        Span parent = tracer.spanBuilder("plain-parent").startSpan();

        // when
        Span child;
        try (Scope ignored = parent.makeCurrent()) {
            child = tracer.spanBuilder("plain-child").startSpan();
        }
        child.end();
        parent.end();

        // then
        assertThat(attribute(exporter, "plain-child")).isNull();
    }

    @Test
    void doesNotCopyEmptyRequestIdFromParent(OpenTelemetrySdk sdk, InMemorySpanExporter exporter) {
        // given
        Tracer tracer = sdk.getTracer("request-id-propagation");
        Span parent = tracer.spanBuilder("blank-parent").startSpan();
        parent.setAttribute(REQUEST_ID, "");

        // when
        Span child;
        try (Scope ignored = parent.makeCurrent()) {
            child = tracer.spanBuilder("blank-child").startSpan();
        }
        child.end();
        parent.end();

        // then
        assertThat(attribute(exporter, "blank-child")).isNull();
    }

    @Test
    void doesNotBackfillChildStartedBeforeParentReceivesRequestId(OpenTelemetrySdk sdk,
                                                                   InMemorySpanExporter exporter) {
        // given
        Tracer tracer = sdk.getTracer("request-id-propagation");
        Span parent = tracer.spanBuilder("late-parent").startSpan();
        Span child = tracer.spanBuilder("late-child")
                .setParent(Context.root().with(parent))
                .startSpan();

        // when
        parent.setAttribute(REQUEST_ID, "late-id");
        child.end();
        parent.end();

        // then
        assertThat(attribute(exporter, "late-child")).isNull();
        assertThat(attribute(exporter, "late-parent")).isEqualTo("late-id");
    }

    @Test
    void doesNotCopyRequestIdFromSpanLink(OpenTelemetrySdk sdk, InMemorySpanExporter exporter) {
        // given
        Tracer tracer = sdk.getTracer("request-id-propagation");
        Span linked = tracer.spanBuilder("linked-target").startSpan();
        linked.setAttribute(REQUEST_ID, "linked-id");

        // when
        Span span = tracer.spanBuilder("link-only")
                .addLink(linked.getSpanContext())
                .startSpan();
        span.end();
        linked.end();

        // then
        assertThat(attribute(exporter, "link-only")).isNull();
    }

    @Test
    void copiesFromEndedLocalParent(OpenTelemetrySdk sdk, InMemorySpanExporter exporter) {
        // given
        Tracer tracer = sdk.getTracer("request-id-propagation");
        Span parent = tracer.spanBuilder("ended-parent").startSpan();
        parent.setAttribute(REQUEST_ID, "ended-id");
        parent.end();

        // when
        Span child = tracer.spanBuilder("ended-child")
                .setParent(Context.root().with(parent))
                .startSpan();
        child.end();

        // then
        assertThat(attribute(exporter, "ended-child")).isEqualTo("ended-id");
    }

    private static String attribute(InMemorySpanExporter exporter, String name) {
        List<SpanData> spans = exporter.getFinishedSpanItems();
        SpanData span = spans.stream()
                .filter(item -> name.equals(item.getName()))
                .findFirst()
                .orElseThrow();
        return span.getAttributes().get(REQUEST_ID);
    }

    /**
     * Ставит атрибут до наследования, чтобы проверить отказ от перезаписи.
     */
    private static final class PresetChildAttributeProcessor implements SpanProcessor {

        @Override
        public void onStart(@Nonnull Context parentContext, @Nonnull ReadWriteSpan span) {
            String name = span.getName();
            if ("sealed-child".equals(name)) {
                span.setAttribute(REQUEST_ID, "");
            } else if ("kept-child".equals(name)) {
                span.setAttribute(REQUEST_ID, "keep-me");
            }
        }

        @Override
        public boolean isStartRequired() {
            return true;
        }

        @Override
        public void onEnd(@Nonnull ReadableSpan span) {
        }

        @Override
        public boolean isEndRequired() {
            return false;
        }
    }
}
