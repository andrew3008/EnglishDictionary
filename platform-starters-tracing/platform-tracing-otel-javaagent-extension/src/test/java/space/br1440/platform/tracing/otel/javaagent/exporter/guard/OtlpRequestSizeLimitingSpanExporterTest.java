package space.br1440.platform.tracing.otel.javaagent.exporter.guard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import space.br1440.platform.tracing.otel.javaagent.configuration.OtelSdkDefaults;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSourceTestFixtures;
import space.br1440.platform.tracing.otel.javaagent.exporter.SafeSpanExporter;
import space.br1440.platform.tracing.otel.javaagent.exporter.response.ExportResponseObservation;
import space.br1440.platform.tracing.otel.javaagent.exporter.response.ExportResponseScope;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.JmxWireContract;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLogCapture;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime.ScrubbingRuntimeTestFixtures;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.Value;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.DelegatingSpanData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;

@Timeout(15)
@ResourceLock(AgentLogCapture.RESOURCE_LOCK)
class OtlpRequestSizeLimitingSpanExporterTest {

    private final SdkTracerProvider provider = SdkTracerProvider.builder()
            .setResource(Resource.empty())
            .addSpanProcessor(SimpleSpanProcessor.create(SpanExporter.composite())).build();

    @AfterEach
    void closeProvider() {
        provider.close();
    }

    @Test
    void requestLimitCannotExceedThePlatformBound() {
        // given
        SpanExporter delegate = exporter(spans -> CompletableResultCode.ofSuccess());

        // when
        // then
        assertThatThrownBy(() -> new OtlpRequestSizeLimitingSpanExporter(delegate,
                Math.toIntExact(OtelSdkDefaults.MAX_OTLP_REQUEST_SIZE_BYTES) + 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("platform limit");
    }

    @Test
    void ordinaryFailureIsReturnedWithoutSplittingOrRetry() {
        // given
        AtomicInteger calls = new AtomicInteger();
        OtlpRequestSizeLimitingSpanExporter guard = guard(exporter(spans -> {
            calls.incrementAndGet();
            return CompletableResultCode.ofFailure();
        }));
        ExportResponseObservation parent = new ExportResponseObservation(1);

        // when
        CompletableResultCode result = export(guard, List.of(span("small")), parent);

        // then
        assertThat(result.isDone()).isTrue();
        assertThat(result.isSuccess()).isFalse();
        assertThat(calls).hasValue(1);
        assertThat(parent.aggregateResult()).isEqualTo(outcome(0, 0, 1, 0, 0, 1));
        assertThat(ExportResponseScope.current()).isNull();
    }

    @Test
    void singleChunkGoesThroughTheSameLeafPathAsMany() {
        // given
        ExportResponseObservation parent = new ExportResponseObservation(1);
        List<ExportResponseObservation> leaves = new ArrayList<>();
        OtlpRequestSizeLimitingSpanExporter guard = guard(exporter(spans -> {
            ExportResponseObservation leaf = ExportResponseScope.current();
            leaves.add(leaf);
            leaf.recordAllAccepted();
            assertThat(parent.aggregateResult()).isNull();
            return CompletableResultCode.ofSuccess();
        }));

        // when
        CompletableResultCode result = export(guard, List.of(span("small")), parent);

        // then
        assertThat(result.isSuccess()).isTrue();
        assertThat(leaves).singleElement().isNotSameAs(parent);
        assertThat(parent.outcome()).isEqualTo(ExportResponseObservation.Outcome.NOT_OBSERVED);
        assertThat(parent.aggregateResult()).isEqualTo(outcome(1, 0, 0, 0, 0, 1));
    }

    @Test
    void neighboursThatFitTogetherLeaveAsOneRequest() {
        // given
        SpanData left = span("left");
        SpanData middle = span("middle");
        SpanData right = span("right");
        SpanData large = span("x".repeat(4000));
        List<List<SpanData>> sent = new ArrayList<>();
        OtlpRequestSizeLimitingSpanExporter guard = guard(exporter(spans -> {
            sent.add(List.copyOf(spans));
            return CompletableResultCode.ofSuccess();
        }));
        ExportResponseObservation parent = new ExportResponseObservation(6);

        // when
        CompletableResultCode result = export(guard, List.of(large, left, large, middle, right, large), parent);

        // then
        assertThat(result.isSuccess()).isTrue();
        assertThat(sent).containsExactly(List.of(left, middle, right));
        assertThat(parent.aggregateResult()).isEqualTo(outcome(3, 0, 0, 0, 3, 1));
    }

    @ParameterizedTest
    @ValueSource(ints = {2000, 100000})
    void locallyDroppedOnlyBatchCompletesWithoutDelegate(int nameLength) {
        // given
        AtomicInteger calls = new AtomicInteger();
        OtlpRequestSizeLimitingSpanExporter guard = guard(exporter(spans -> {
            calls.incrementAndGet();
            return CompletableResultCode.ofSuccess();
        }));
        SpanData large = span("x".repeat(nameLength));
        ExportResponseObservation parent = new ExportResponseObservation(2);

        // when
        CompletableResultCode result = export(guard, List.of(large, large), parent);

        // then
        assertThat(result.isSuccess()).isTrue();
        assertThat(calls).hasValue(0);
        assertThat(parent.aggregateResult()).isEqualTo(outcome(0, 0, 0, 0, 2, 0));
    }

    @Test
    void heavyLegalBatchIsPackedGreedilyAndLaunchedBeforeAnyCompletes() {
        // given
        List<List<SpanData>> sent = new ArrayList<>();
        List<CompletableResultCode> pending = new ArrayList<>();
        OtlpRequestSizeLimitingSpanExporter guard = guard(exporter(spans -> {
            sent.add(List.copyOf(spans));
            CompletableResultCode result = new CompletableResultCode();
            pending.add(result);
            return result;
        }));
        SpanData small = span("s".repeat(300));
        SpanData large = span("l".repeat(800));
        List<SpanData> input = List.of(small, small, large, small, small);
        ExportResponseObservation parent = new ExportResponseObservation(input.size());

        // when
        CompletableResultCode result = export(guard, input, parent);

        // then
        assertThat(sent).containsExactly(List.of(small, small), List.of(large), List.of(small, small));
        assertThat(pending).hasSize(3).allSatisfy(leaf -> assertThat(leaf.isDone()).isFalse());
        assertThat(parent.aggregateResult()).isNull();
        pending.reversed().forEach(CompletableResultCode::succeed);
        assertThat(result.isSuccess()).isTrue();
        assertThat(parent.aggregateResult()).isEqualTo(outcome(5, 0, 0, 0, 0, 3));
    }

    @Test
    void failedChunkCountsOnlyItsSpans() {
        // given
        AtomicInteger calls = new AtomicInteger();
        OtlpRequestSizeLimitingSpanExporter guard = guard(exporter(spans -> {
            if (calls.incrementAndGet() == 2) {
                ExportResponseScope.current().recordAllAccepted();
                return CompletableResultCode.ofFailure();
            }
            return CompletableResultCode.ofSuccess();
        }));
        SpanData small = span("s".repeat(300));
        SpanData large = span("l".repeat(800));
        ExportResponseObservation parent = new ExportResponseObservation(4);

        // when
        CompletableResultCode result = export(guard, List.of(large, small, small, large), parent);

        // then
        assertThat(result.isSuccess()).isFalse();
        assertThat(calls).hasValue(3);
        assertThat(parent.aggregateResult()).isEqualTo(outcome(2, 0, 2, 0, 0, 3));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void firstChunkThrowingOrReturningNullDoesNotCancelOtherChunks(boolean returnsNull) {
        // given
        AtomicInteger calls = new AtomicInteger();
        IllegalStateException failure = new IllegalStateException("Simulated export failure");
        OtlpRequestSizeLimitingSpanExporter guard = guard(exporter(spans -> {
            if (calls.incrementAndGet() == 1) {
                if (returnsNull) {
                    return null;
                }
                throw failure;
            }
            return CompletableResultCode.ofSuccess();
        }));
        SpanData large = span("l".repeat(800));
        ExportResponseObservation parent = new ExportResponseObservation(3);

        // when
        CompletableResultCode result = export(guard, List.of(large, large, large), parent);

        // then
        assertThat(result.isSuccess()).isFalse();
        assertThat(calls).hasValue(3);
        assertThat(result.getFailureThrowable()).isInstanceOf(returnsNull ? NullPointerException.class : IllegalStateException.class);
        assertThat(parent.aggregateResult()).isEqualTo(outcome(2, 0, 1, 0, 0, 3));
    }

    @Test
    void synchronousLeavesPublishAggregateBeforeExportReturns() {
        // given
        AtomicInteger calls = new AtomicInteger();
        List<ExportResponseObservation> leaves = new ArrayList<>();
        OtlpRequestSizeLimitingSpanExporter guard = guard(exporter(spans -> {
            ExportResponseObservation leaf = ExportResponseScope.current();
            leaves.add(leaf);
            if (calls.incrementAndGet() == 1) {
                leaf.recordRejected(1);
            } else {
                leaf.recordNotDetermined();
            }
            return CompletableResultCode.ofSuccess();
        }));
        ExportResponseObservation parent = new ExportResponseObservation(3);

        // when
        CompletableResultCode result = export(guard,
                List.of(span("a".repeat(800)), span("b".repeat(800)), span("x".repeat(4000))), parent);

        // then
        assertThat(result.isSuccess()).isTrue();
        assertThat(leaves).hasSize(2).doesNotContain(parent);
        assertThat(leaves.getFirst()).isNotSameAs(leaves.getLast());
        assertThat(parent.aggregateResult()).isEqualTo(outcome(0, 1, 0, 1, 1, 2));
        assertThat(ExportResponseScope.current()).isNull();
    }

    @Test
    void asynchronousCompletionPublishesOnlyAfterTheLastLeafAndRestoresScope() throws InterruptedException {
        // given
        CompletableResultCode first = new CompletableResultCode();
        CompletableResultCode second = new CompletableResultCode();
        AtomicInteger calls = new AtomicInteger();
        OtlpRequestSizeLimitingSpanExporter guard = guard(exporter(spans -> calls.incrementAndGet() == 1 ? first : second));
        ExportResponseObservation parent = new ExportResponseObservation(2);
        CompletableResultCode result = export(guard, List.of(span("a".repeat(800)), span("b".repeat(800))), parent);
        AtomicInteger completions = new AtomicInteger();
        result.whenComplete(completions::incrementAndGet);
        ExportResponseObservation unrelated = new ExportResponseObservation(0);
        AtomicBoolean restored = new AtomicBoolean();

        // when
        Thread completionThread = new Thread(() -> {
            try (ExportResponseScope.Handle ignored = ExportResponseScope.open(unrelated)) {
                second.succeed();
                restored.set(ExportResponseScope.current() == unrelated);
            }
        });
        completionThread.start();
        completionThread.join();

        // then
        assertThat(restored).isTrue();
        assertThat(calls).hasValue(2);
        assertThat(parent.aggregateResult()).isNull();
        assertThat(result.isDone()).isFalse();
        first.succeed();
        assertThat(result.isSuccess()).isTrue();
        assertThat(completions).hasValue(1);
        assertThat(parent.aggregateResult()).isEqualTo(outcome(2, 0, 0, 0, 0, 2));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void measurementExceptionIsMeasurementFailureNotOversized(boolean preflightFails) {
        // given
        IllegalArgumentException failure = new IllegalArgumentException("Simulated measurement failure");
        SpanData broken = new DelegatingSpanData(span("broken")) {
            @Override
            public String getName() {
                if (preflightFails) {
                    throw failure;
                }

                return super.getName();
            }
        };
        List<SpanData> sent = new ArrayList<>();
        OtlpRequestSizeLimitingSpanExporter guard = new OtlpRequestSizeLimitingSpanExporter(exporter(spans -> {
            sent.addAll(spans);
            return CompletableResultCode.ofSuccess();
        }), 1000, () -> candidate -> {
            if (candidate == broken) {
                throw failure;
            }

            return 100;
        });
        SpanData small = span("small");
        ExportResponseObservation parent = new ExportResponseObservation(3);

        // when
        CompletableResultCode result = export(guard, List.of(broken, small, broken), parent);

        // then
        assertThat(result.isSuccess()).isTrue();
        assertThat(sent).containsExactly(small);
        assertThat(parent.aggregateResult()).isEqualTo(new ExportResponseObservation.AggregateResult(1, 0, 0, 0, 0, 2, 1, failure));
    }

    @Test
    void negativeSizeIsMeasurementFailureNotOversized() {
        // given
        List<SpanData> sent = new ArrayList<>();
        SpanData broken = span("broken");
        SpanData small = span("small");
        OtlpRequestSizeLimitingSpanExporter guard = new OtlpRequestSizeLimitingSpanExporter(exporter(spans -> {
            sent.addAll(spans);
            return CompletableResultCode.ofSuccess();
        }), 1000, () -> candidate -> candidate == broken ? -1 : 100);
        ExportResponseObservation parent = new ExportResponseObservation(2);

        // when
        CompletableResultCode result = export(guard, List.of(broken, small), parent);

        // then
        assertThat(result.isSuccess()).isTrue();
        assertThat(sent).containsExactly(small);
        assertThat(parent.aggregateResult().measurementFailed()).isEqualTo(1);
        assertThat(parent.aggregateResult().locallyDropped()).isZero();
        assertThat(parent.aggregateResult().total()).isEqualTo(2);
        assertThat(parent.aggregateResult().measurementCause()).isInstanceOf(IllegalStateException.class)
                .hasMessage("negative serialized size");
    }

    @Test
    void valueTooDeepForSdkMarshalersIsMeasurementFailureNotStackOverflow() {
        // given
        Value<?> deep = Value.of("x");
        for (int level = 0; level < 100_000; level++) {
            deep = Value.of(deep);
        }

        Resource resource = Resource.create(Attributes.of(AttributeKey.valueKey("v"), deep));
        SpanData broken = new DelegatingSpanData(span("deep")) {
            @Override
            public Resource getResource() {
                return resource;
            }
        };

        List<SpanData> sent = new ArrayList<>();
        OtlpRequestSizeLimitingSpanExporter guard = new OtlpRequestSizeLimitingSpanExporter(exporter(spans -> {
            sent.addAll(spans);
            return CompletableResultCode.ofSuccess();
        }), Math.toIntExact(OtelSdkDefaults.MAX_OTLP_REQUEST_SIZE_BYTES));
        SpanData small = span("small");
        ExportResponseObservation parent = new ExportResponseObservation(2);

        // when
        CompletableResultCode result = export(guard, List.of(broken, small), parent);

        // then
        assertThat(result.isSuccess()).isTrue();
        assertThat(sent).containsExactly(small);
        assertThat(parent.aggregateResult().measurementFailed()).isEqualTo(1);
        assertThat(parent.aggregateResult().locallyDropped()).isZero();
        assertThat(parent.aggregateResult().measurementCause()).isInstanceOf(IllegalStateException.class)
                .hasMessage("attribute value nesting exceeds " + SpanSizeLowerBound.MAX_VALUE_DEPTH);
    }

    @Test
    void fatalMeasurementErrorPropagates() {
        // given
        LinkageError failure = new LinkageError("Simulated measurement linkage failure");
        SpanData broken = new DelegatingSpanData(span("broken")) {
            @Override
            public String getName() {
                throw failure;
            }
        };

        AtomicInteger calls = new AtomicInteger();
        OtlpRequestSizeLimitingSpanExporter guard = guard(exporter(spans -> {
            calls.incrementAndGet();
            return CompletableResultCode.ofSuccess();
        }));

        // when
        // then
        assertThatThrownBy(() -> guard.export(List.of(broken), new ExportResponseObservation(1))).isSameAs(failure);
        assertThat(calls).hasValue(0);
    }

    @Test
    void oversizedSpanIsDroppedWhileNeighborsAreExported() {
        // given
        List<SpanData> sent = new ArrayList<>();
        SpanExporter delegate = exporter(spans -> {
            sent.addAll(spans);
            return CompletableResultCode.ofSuccess();
        });

        SafeSpanExporter safe = new SafeSpanExporter(new OtlpRequestSizeLimitingSpanExporter(delegate, 2000),
                TracingSettingsSourceTestFixtures.boundDefaults(AgentInstanceId.random()),
                ScrubbingRuntimeTestFixtures.disabled());
        Span large = provider.get("isolation-test").spanBuilder("oversized").startSpan();
        large.setAttribute(AttributeKey.stringArrayKey("payload"), Collections.nCopies(8, "x".repeat(1000)));
        large.end();
        List<SpanData> input = List.of(span("a"), span("b"), ((ReadableSpan) large).toSpanData());

        // when
        CompletableResultCode result = safe.export(input);

        // then
        assertThat(result.isSuccess()).isTrue();
        assertThat(sent).hasSize(2);
        assertThat(safe.metricsSnapshot().getOrDefault(JmxWireContract.KEY_SAFE_EXPORTER_METRICS_OVERSIZED_SPANS_DROPPED, 0L).longValue()).isEqualTo(1);
        assertThat(safe.metricsSnapshot().getOrDefault(JmxWireContract.KEY_SAFE_EXPORTER_METRICS_EXPORTED_SPANS, 0L).longValue()).isEqualTo(2);
        assertThat(safe.metricsSnapshot().getOrDefault(JmxWireContract.KEY_SAFE_EXPORTER_METRICS_TRANSPORT_DROPPED_SPANS, 0L).longValue()).isZero();
        assertThat(safe.metricsSnapshot().getOrDefault(JmxWireContract.KEY_SAFE_EXPORTER_METRICS_EXPORT_BATCH_FAILURES, 0L).longValue()).isZero();
    }

    @Test
    void flushAndShutdownAreDelegated() {
        // given
        AtomicInteger flushed = new AtomicInteger();
        AtomicInteger closed = new AtomicInteger();
        SpanExporter delegate = new SpanExporter() {
            @Override
            public CompletableResultCode export(Collection<SpanData> spans) {
                return CompletableResultCode.ofSuccess();
            }

            @Override
            public CompletableResultCode flush() {
                flushed.incrementAndGet();
                return CompletableResultCode.ofSuccess();
            }

            @Override
            public CompletableResultCode shutdown() {
                closed.incrementAndGet();
                return CompletableResultCode.ofSuccess();
            }
        };
        OtlpRequestSizeLimitingSpanExporter guard = guard(delegate);

        // when
        guard.flush();
        guard.shutdown();

        // then
        assertThat(flushed).hasValue(1);
        assertThat(closed).hasValue(1);
    }

    private static OtlpRequestSizeLimitingSpanExporter guard(SpanExporter delegate) {
        return new OtlpRequestSizeLimitingSpanExporter(delegate, 1000);
    }

    private SpanData span(String name) {
        Span span = provider.get("isolation-test").spanBuilder(name).startSpan();
        span.end();
        return ((ReadableSpan) span).toSpanData();
    }

    private static ExportResponseObservation.AggregateResult outcome(int accepted, int rejected, int dropped,
                                                                    int undetermined, int oversized, int requests) {
        return new ExportResponseObservation.AggregateResult(accepted, rejected, dropped, undetermined, oversized, 0, requests, null);
    }

    private static CompletableResultCode export(OtlpRequestSizeLimitingSpanExporter exporter,
                                               List<SpanData> spans,
                                               ExportResponseObservation observation) {
        return exporter.export(spans, observation);
    }

    private static SpanExporter exporter(Function<Collection<SpanData>, CompletableResultCode> export) {
        return new SpanExporter() {
            @Override
            public CompletableResultCode export(Collection<SpanData> spans) {
                return export.apply(spans);
            }

            @Override
            public CompletableResultCode flush() {
                return CompletableResultCode.ofSuccess();
            }

            @Override
            public CompletableResultCode shutdown() {
                return CompletableResultCode.ofSuccess();
            }
        };
    }
}
