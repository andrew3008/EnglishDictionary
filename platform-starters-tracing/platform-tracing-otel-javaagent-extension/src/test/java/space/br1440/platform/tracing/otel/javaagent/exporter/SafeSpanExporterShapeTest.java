package space.br1440.platform.tracing.otel.javaagent.exporter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import space.br1440.platform.tracing.otel.javaagent.control.AcceptedPolicyMutationAccess;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSourceTestFixtures;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.rules.BuiltInSpanAttributeScrubbingRules;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime.ScrubbingRuntime;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime.ScrubbingRuntimeTestFixtures;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.DelegatingSpanData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

class SafeSpanExporterShapeTest {

    @ParameterizedTest
    @EnumSource(StatusCode.class)
    void limitsNamesAndOnlyErrorDescriptions(StatusCode status) {
        // given
        SpanData exact = span("n".repeat(1024), status, "d".repeat(1024));
        SpanData oversized = span("n".repeat(1025), status, "d".repeat(1025));
        InMemorySpanExporter delegate = InMemorySpanExporter.create();
        SafeSpanExporter safe = exporter(delegate, ScrubbingRuntimeTestFixtures.disabled());

        // when
        safe.export(List.of(exact, oversized));

        // then
        List<SpanData> exported = delegate.getFinishedSpanItems();
        assertThat(exported.getFirst()).isSameAs(exact);
        SpanData limited = exported.getLast();
        assertThat(limited.getName()).isEqualTo("n".repeat(1024));
        assertThat(limited.getStatus().getStatusCode()).isEqualTo(status);
        assertThat(limited.getStatus().getDescription())
                .isEqualTo(status == StatusCode.ERROR ? "d".repeat(1024) : "");
        assertThat(safe.getShapeTruncationsTotal()).isEqualTo(1);
        assertThat(safe.getExportedSpans()).isEqualTo(2);
        assertThat(safe.getExportFailures()).isZero();
        assertThat(safe.getDroppedSpans()).isZero();
    }

    @Test
    void limitsDescriptionWithoutChangingTheNameOrOtherSpanData() {
        // given
        SpanData input = span("operation", StatusCode.ERROR, "d".repeat(1025));
        InMemorySpanExporter delegate = InMemorySpanExporter.create();
        SafeSpanExporter safe = exporter(delegate, ScrubbingRuntimeTestFixtures.disabled());

        // when
        safe.export(List.of(input));

        // then
        SpanData output = delegate.getFinishedSpanItems().getFirst();
        assertThat(output.getName()).isSameAs(input.getName());
        assertThat(output.getStatus().getDescription()).hasSize(1024);
        assertThat(output.getSpanContext()).isEqualTo(input.getSpanContext());
        assertThat(output.getParentSpanContext()).isEqualTo(input.getParentSpanContext());
        assertThat(output.getResource()).isSameAs(input.getResource());
        assertThat(output.getInstrumentationScopeInfo()).isSameAs(input.getInstrumentationScopeInfo());
        assertThat(output.getAttributes()).isSameAs(input.getAttributes());
        assertThat(output.getEvents()).isSameAs(input.getEvents());
        assertThat(output.getLinks()).isSameAs(input.getLinks());
        assertThat(output.getStartEpochNanos()).isEqualTo(input.getStartEpochNanos());
        assertThat(output.getEndEpochNanos()).isEqualTo(input.getEndEpochNanos());
        assertThat(output.getTotalAttributeCount()).isEqualTo(input.getTotalAttributeCount());
        assertThat(output.getTotalRecordedEvents()).isEqualTo(input.getTotalRecordedEvents());
        assertThat(output.getTotalRecordedLinks()).isEqualTo(input.getTotalRecordedLinks());
        assertThat(output.getAttributes().get(AttributeKey.stringArrayKey("items"))).hasSize(200);
        assertThat(safe.getShapeTruncationsTotal()).isEqualTo(1);
    }

    @Test
    void scrubsCompleteJwtBeforeLimitingEitherTextSurface() {
        // given
        String jwt = " ".repeat(1000) + "eyJhbGciOiJIUzI1NiJ9.eyJ1c2VyIjoic2VjcmV0In0.sig";
        ScrubbingRuntime runtime = ExporterBoundaryTestSupport.runtimeOf(
                List.of(BuiltInSpanAttributeScrubbingRules.JWT.create()));
        InMemorySpanExporter delegate = InMemorySpanExporter.create();
        SafeSpanExporter safe = exporter(delegate, runtime);

        // when
        safe.export(List.of(span(jwt, StatusCode.ERROR, jwt)));

        // then
        SpanData output = delegate.getFinishedSpanItems().getFirst();
        assertThat(output.getName()).isEqualTo("<redacted>");
        assertThat(output.getStatus().getDescription()).isEmpty();
        assertThat(safe.getShapeTruncationsTotal()).isZero();
    }

    @ParameterizedTest
    @ValueSource(ints = {1012, 4097})
    void preservesEmailScrubbingAcrossLengthBoundaries(int prefixLength) {
        // given
        String email = " ".repeat(prefixLength) + "alice@example.com";
        ScrubbingRuntime runtime = ExporterBoundaryTestSupport.runtimeOf(
                List.of(BuiltInSpanAttributeScrubbingRules.EMAIL.create()));
        InMemorySpanExporter delegate = InMemorySpanExporter.create();
        SafeSpanExporter safe = exporter(delegate, runtime);

        // when
        safe.export(List.of(span(email, StatusCode.ERROR, email)));

        // then
        SpanData output = delegate.getFinishedSpanItems().getFirst();
        assertThat(output.getName()).isEqualTo("<redacted>");
        assertThat(output.getStatus().getDescription()).isEqualTo("<redacted>");
        assertThat(safe.getShapeTruncationsTotal()).isZero();
    }

    @Test
    void exposesShapeCounterWhenScrubbingIsDisabled() {
        // given
        InMemorySpanExporter delegate = InMemorySpanExporter.create();
        SafeSpanExporter safe = exporter(delegate, ScrubbingRuntimeTestFixtures.disabled());
        SpanData input = span("n".repeat(1025), StatusCode.ERROR, "d".repeat(1025));

        // when
        safe.export(List.of(input));

        // then
        assertThat(safe.metricsSnapshot()).containsEntry("shape_truncations_total", 1L);
        assertThat(safe.metricsSnapshot()).containsEntry("oversized_spans_dropped", 0L);
        assertThat(delegate.getFinishedSpanItems()).hasSize(1);
    }

    @Test
    void exportGatePreventsShapePreparationAndCounting() {
        // given
        TracingSettingsSource policy = TracingSettingsSourceTestFixtures.boundDefaults(AgentInstanceId.random());
        AcceptedPolicyMutationAccess.applyExporter(policy, false);
        InMemorySpanExporter delegate = InMemorySpanExporter.create();
        SafeSpanExporter safe = new SafeSpanExporter(delegate, policy, ScrubbingRuntimeTestFixtures.disabled());
        SpanData input = span("n".repeat(1025), StatusCode.ERROR, "d".repeat(1025));

        // when
        safe.export(List.of(input));

        // then
        assertThat(safe.getShapeTruncationsTotal()).isZero();
        assertThat(safe.getGatedSpans()).isEqualTo(1);
        assertThat(delegate.getFinishedSpanItems()).isEmpty();
    }

    @Test
    void boundaryDropDoesNotCountShapeTruncation() {
        // given
        SpanData input = new DelegatingSpanData(span("n".repeat(1025), StatusCode.ERROR, "d".repeat(1025))) {
            @Override
            public Attributes getAttributes() {
                throw new IllegalStateException("Corrupted span attributes");
            }
        };
        ScrubbingRuntime runtime = ExporterBoundaryTestSupport.runtimeOf(List.of());
        InMemorySpanExporter delegate = InMemorySpanExporter.create();
        SafeSpanExporter safe = exporter(delegate, runtime);

        // when
        safe.export(List.of(input));

        // then
        assertThat(safe.getShapeTruncationsTotal()).isZero();
        assertThat(delegate.getFinishedSpanItems()).isEmpty();
        assertThat(safe.getExportFailures()).isZero();
        assertThat(runtime.boundaryMetrics().spansDroppedFailClosed()).isEqualTo(1);
    }

    @Test
    void cleanBatchRetainsIdentityWithScrubbingDisabled() {
        // given
        SpanData input = span("operation", StatusCode.UNSET, "");
        InMemorySpanExporter delegate = InMemorySpanExporter.create();
        SafeSpanExporter safe = exporter(delegate, ScrubbingRuntimeTestFixtures.disabled());

        // when
        safe.export(List.of(input));

        // then
        assertThat(delegate.getFinishedSpanItems().getFirst()).isSameAs(input);
        assertThat(safe.getShapeTruncationsTotal()).isZero();
    }

    private static SafeSpanExporter exporter(InMemorySpanExporter delegate, ScrubbingRuntime runtime) {
        return new SafeSpanExporter(delegate,
                TracingSettingsSourceTestFixtures.boundDefaults(AgentInstanceId.random()), runtime);
    }

    private static SpanData span(String name, StatusCode status, String description) {
        InMemorySpanExporter collector = InMemorySpanExporter.create();
        try (SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(collector))
                .build()) {
            Span span = provider.get("shape-test").spanBuilder(name)
                    .setNoParent()
                    .addLink(SpanContext.create("1".repeat(32), "2".repeat(16),
                            TraceFlags.getSampled(), TraceState.getDefault()))
                    .startSpan();
            span.setStatus(status, description);
            span.setAttribute(AttributeKey.stringArrayKey("items"), Collections.nCopies(200, "value"));
            span.addEvent("event", Attributes.of(AttributeKey.stringKey("kind"), "value"));
            span.end();
            return collector.getFinishedSpanItems().getFirst();
        }
    }
}
