package space.br1440.platform.tracing.otel.javaagent.exporter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import space.br1440.platform.tracing.otel.javaagent.configuration.OtelSdkDefaults;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSourceTestFixtures;
import space.br1440.platform.tracing.otel.javaagent.exporter.response.ExportResponseObservation;
import space.br1440.platform.tracing.otel.javaagent.exporter.response.ExportResponseObservation.AggregateResult;
import space.br1440.platform.tracing.otel.javaagent.exporter.response.ExportResponseScope;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLogCapture;
import space.br1440.platform.tracing.otel.javaagent.logging.CapturedLogEvent;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogLevel;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime.ScrubbingRuntimeTestFixtures;

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;

@ResourceLock(AgentLogCapture.RESOURCE_LOCK)
class SafeSpanExporterAggregateTest {

    @Test
    void creditsEveryAggregateCategoryDespiteTransportFailure() {
        // given
        AggregateResult outcome = new AggregateResult(2, 1, 1, 1, 1);
        try (SafeSpanExporter safe = exporter(new Step(outcome, CompletableResultCode.ofFailure()))) {
            // when
            CompletableResultCode result = safe.export(batch(6));

            // then
            assertThat(result.isSuccess()).isFalse();
            assertThat(safe.getExportBatches()).isEqualTo(1);
            assertThat(safe.getExportFailures()).isEqualTo(1);
            assertThat(safe.getExportedSpans()).isEqualTo(2);
            assertThat(safe.getRejectedSpans()).isEqualTo(1);
            assertThat(safe.getDroppedSpans()).isEqualTo(1);
            assertThat(safe.getOversizedSpansDropped()).isEqualTo(1);
            assertThat(safe.getAcceptedUnknownBatches()).isEqualTo(1);
            assertThat(safe.getPartialSuccessBatches()).isEqualTo(1);
            assertThat(safe.metricsSnapshot()).containsEntry("oversized_spans_dropped", 1L);
        }
    }

    @Test
    void localDropsHaveSeparateAccountingAndNumericOnlyDiagnostic() {
        // given
        SpanData input = ExporterBoundaryTestSupport.baseSpan("sensitive-operation").build();
        try (SafeSpanExporter safe = exporter(new Step(new AggregateResult(0, 0, 0, 0, 2),
                CompletableResultCode.ofSuccess()));
             AgentLogCapture capture = AgentLogCapture.installed()) {
            // when
            CompletableResultCode result = safe.export(List.of(input, input));

            // then
            assertThat(result.isSuccess()).isTrue();
            assertThat(safe.getOversizedSpansDropped()).isEqualTo(2);
            assertThat(safe.getDroppedSpans()).isZero();
            assertThat(safe.getExportedSpans()).isZero();
            assertThat(safe.getExportFailures()).isZero();
            assertThat(capture.events())
                    .filteredOn(event -> "SAFE_EXPORT_OVERSIZED_SPAN_DROPPED".equals(event.eventName()))
                    .singleElement().satisfies(event -> {
                        assertThat(event.level()).isEqualTo(AgentLogLevel.WARN);
                        assertThat(event.fields()).containsOnlyKeys("exporterType", "count", "limitBytes");
                        assertThat(event.fields()).containsEntry("count", "2");
                        assertThat(event.fields()).containsEntry("limitBytes",
                                Long.toString(OtelSdkDefaults.MAX_OTLP_REQUEST_SIZE_BYTES));
                        assertThat(event.throwableDigest()).isNull();
                    });
            assertThat(capture.rendered()).doesNotContain("sensitive-operation");
        }
    }

    @Test
    void mixedFailureAndLocalDropsKeepTheRecoveryEpisodeOpen() {
        // given
        try (SafeSpanExporter safe = exporter(
                new Step(new AggregateResult(0, 0, 2, 0, 0), CompletableResultCode.ofFailure()),
                new Step(new AggregateResult(1, 0, 1, 0, 0), CompletableResultCode.ofFailure()),
                new Step(new AggregateResult(0, 0, 0, 0, 2), CompletableResultCode.ofSuccess()),
                new Step(new AggregateResult(1, 1, 0, 0, 0), CompletableResultCode.ofSuccess()));
             AgentLogCapture capture = AgentLogCapture.installed()) {
            // when
            safe.export(batch(2));
            safe.export(batch(2));
            safe.export(batch(2));

            // then
            assertThat(recoveryEvents(capture)).isEmpty();
            assertThat(safe.getExportFailures()).isEqualTo(2);
            assertThat(safe.getDroppedSpans()).isEqualTo(3);
            assertThat(safe.getExportedSpans()).isEqualTo(1);

            // when
            safe.export(batch(2));

            // then
            assertThat(recoveryEvents(capture)).singleElement()
                    .satisfies(event -> assertThat(event.fields()).containsEntry("failures", "2"));
            assertThat(safe.getExportedSpans()).isEqualTo(2);
            assertThat(safe.getRejectedSpans()).isEqualTo(1);
            assertThat(safe.getOversizedSpansDropped()).isEqualTo(2);
        }
    }

    @ParameterizedTest
    @MethodSource("successfulResponses")
    void successfulObservedLeafCanCloseRecovery(AggregateResult outcome) {
        // given
        try (SafeSpanExporter safe = exporter(
                new Step(new AggregateResult(0, 0, 1, 0, 0), CompletableResultCode.ofFailure()),
                new Step(outcome, CompletableResultCode.ofSuccess()));
             AgentLogCapture capture = AgentLogCapture.installed()) {
            safe.export(batch(1));

            // when
            safe.export(batch(1));

            // then
            assertThat(recoveryEvents(capture)).hasSize(1);
            assertThat(safe.getExportFailures()).isEqualTo(1);
        }
    }

    @Test
    void lateCompletionAppliesAggregateOnlyOnce() {
        // given
        CompletableResultCode pending = new CompletableResultCode();
        try (SafeSpanExporter safe = exporter(new Step(new AggregateResult(1, 0, 0, 0, 1), pending))) {
            CompletableResultCode returned = safe.export(batch(2));

            // when
            pending.succeed();
            pending.succeed();

            // then
            assertThat(returned).isSameAs(pending);
            assertThat(safe.getExportedSpans()).isEqualTo(1);
            assertThat(safe.getOversizedSpansDropped()).isEqualTo(1);
            assertThat(safe.getExportFailures()).isZero();
        }
    }

    private static Stream<AggregateResult> successfulResponses() {
        return Stream.of(new AggregateResult(1, 0, 0, 0, 0),
                new AggregateResult(0, 1, 0, 0, 0), new AggregateResult(0, 0, 0, 1, 0));
    }

    private static SafeSpanExporter exporter(Step... steps) {
        return new SafeSpanExporter(new SequenceExporter(steps),
                TracingSettingsSourceTestFixtures.boundDefaults(AgentInstanceId.random()),
                ScrubbingRuntimeTestFixtures.disabled());
    }

    private static Collection<SpanData> batch(int size) {
        return Collections.nCopies(size, ExporterBoundaryTestSupport.baseSpan("operation").build());
    }

    private static List<CapturedLogEvent> recoveryEvents(AgentLogCapture capture) {
        return capture.events().stream()
                .filter(event -> "SAFE_EXPORT_SUCCEEDED_AFTER_FAILURES".equals(event.eventName())).toList();
    }

    private record Step(AggregateResult outcome, CompletableResultCode result) {
    }

    private static final class SequenceExporter implements SpanExporter {

        private final Deque<Step> steps;

        private SequenceExporter(Step... steps) {
            this.steps = new ArrayDeque<>(List.of(steps));
        }

        @Override
        public CompletableResultCode export(Collection<SpanData> spans) {
            Step step = steps.removeFirst();
            ExportResponseObservation observation = Objects.requireNonNull(ExportResponseScope.current());
            observation.recordAggregate(step.outcome());
            return step.result();
        }

        @Override
        public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }
    }
}
