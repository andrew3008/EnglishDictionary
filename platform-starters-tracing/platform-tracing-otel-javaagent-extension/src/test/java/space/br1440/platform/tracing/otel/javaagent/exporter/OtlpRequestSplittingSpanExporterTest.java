package space.br1440.platform.tracing.otel.javaagent.exporter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSourceTestFixtures;
import space.br1440.platform.tracing.otel.javaagent.exporter.response.ExportResponseObservation;
import space.br1440.platform.tracing.otel.javaagent.exporter.response.ExportResponseScope;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLogCapture;
import space.br1440.platform.tracing.otel.javaagent.processor.dropoldest.PlatformDropOldestExportSpanProcessor;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime.ScrubbingRuntimeTestFixtures;

import io.opentelemetry.api.common.AttributeKey;
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
class OtlpRequestSplittingSpanExporterTest {

    private final SdkTracerProvider provider = SdkTracerProvider.builder()
            .setResource(Resource.empty())
            .addSpanProcessor(SimpleSpanProcessor.create(SpanExporter.composite())).build();

    @AfterEach
    void closeProvider() {
        provider.close();
    }

    @Test
    void ordinaryFailureIsReturnedWithoutSplittingOrRetry() {
        // given
        AtomicInteger calls = new AtomicInteger();
        CompletableResultCode failure = CompletableResultCode.ofFailure();
        SpanExporter delegate = exporter(spans -> {
            calls.incrementAndGet();
            return failure;
        });
        OtlpRequestSplittingSpanExporter isolator = isolator(delegate);
        ExportResponseObservation parent = new ExportResponseObservation(1);

        // when
        CompletableResultCode result = export(isolator, List.of(span("small")), parent);

        // then
        assertThat(result).isSameAs(failure);
        assertThat(calls).hasValue(1);
        assertThat(parent.aggregateResult()).isNull();
        assertThat(ExportResponseScope.current()).isNull();
    }

    @Test
    void nullDelegateResultOnTheFastPathFailsWithoutSplitting() {
        // given
        AtomicInteger calls = new AtomicInteger();
        OtlpRequestSplittingSpanExporter splitter = isolator(exporter(spans -> {
            calls.incrementAndGet();
            return null;
        }));

        // when
        CompletableResultCode result = splitter.export(List.of(span("small")));

        // then
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getFailureThrowable())
                .isInstanceOf(NullPointerException.class)
                .hasMessage("delegate export result");
        assertThat(calls).hasValue(1);
    }

    @Test
    void oversizedSpansAreDroppedAndNeighboursRemainInOrder() {
        // given
        SpanData left = span("a".repeat(800));
        SpanData middle = span("b".repeat(800));
        SpanData right = span("c".repeat(800));
        SpanData large = span("x".repeat(4000));
        List<SpanData> sent = new ArrayList<>();
        OtlpRequestSplittingSpanExporter isolator = isolator(exporter(spans -> {
            sent.addAll(spans);
            return CompletableResultCode.ofSuccess();
        }));
        List<SpanData> input = List.of(large, left, large, middle, right, large);
        ExportResponseObservation parent = new ExportResponseObservation(input.size());

        // when
        CompletableResultCode result = export(isolator, input, parent);

        // then
        assertThat(result.isSuccess()).isTrue();
        assertThat(sent).containsExactly(left, middle, right);
        assertThat(parent.aggregateResult()).isEqualTo(new ExportResponseObservation.AggregateResult(3, 0, 0, 0, 3));
        assertThat(isolator.export(List.of(left)).isSuccess()).isTrue();
        assertThat(sent).containsExactly(left, middle, right, left);
    }

    @ParameterizedTest
    @ValueSource(ints = {2000, 100000})
    void locallyDroppedOnlyBatchCompletesWithoutDelegate(int nameLength) {
        // given
        AtomicInteger calls = new AtomicInteger();
        OtlpRequestSplittingSpanExporter isolator = isolator(exporter(spans -> {
            calls.incrementAndGet();
            return CompletableResultCode.ofSuccess();
        }));
        SpanData large = span("x".repeat(nameLength));
        ExportResponseObservation parent = new ExportResponseObservation(2);

        // when
        CompletableResultCode result = export(isolator, List.of(large, large), parent);

        // then
        assertThat(result.isSuccess()).isTrue();
        assertThat(calls).hasValue(0);
        assertThat(parent.aggregateResult()).isEqualTo(new ExportResponseObservation.AggregateResult(0, 0, 0, 0, 2));
    }

    @Test
    void firstFailedLeafStopsRemainingAndKeepsPreviousSuccess() {
        // given
        AtomicInteger calls = new AtomicInteger();
        OtlpRequestSplittingSpanExporter isolator = isolator(exporter(spans ->
                calls.incrementAndGet() == 1 ? CompletableResultCode.ofSuccess() : CompletableResultCode.ofFailure()));
        List<SpanData> input = List.of(span("a".repeat(800)), span("b".repeat(800)), span("c".repeat(800)));
        ExportResponseObservation parent = new ExportResponseObservation(input.size());

        // when
        CompletableResultCode result = export(isolator, input, parent);

        // then
        assertThat(result.isSuccess()).isFalse();
        assertThat(calls).hasValue(2);
        assertThat(parent.aggregateResult()).isEqualTo(new ExportResponseObservation.AggregateResult(1, 0, 2, 0, 0));
    }

    @Test
    void partialAndUndeterminedResponsesAreFoldedInTheirOwnScopes() {
        // given
        AtomicInteger calls = new AtomicInteger();
        List<ExportResponseObservation> leaves = new ArrayList<>();
        OtlpRequestSplittingSpanExporter isolator = isolator(exporter(spans -> {
            ExportResponseObservation leaf = ExportResponseScope.current();
            leaves.add(leaf);
            if (calls.incrementAndGet() == 1) {
                leaf.recordRejected(1);
            } else {
                leaf.recordNotDetermined();
            }
            return CompletableResultCode.ofSuccess();
        }));
        List<SpanData> input = List.of(span("a".repeat(800)), span("b".repeat(800)), span("x".repeat(4000)));
        ExportResponseObservation parent = new ExportResponseObservation(input.size());

        // when
        CompletableResultCode result = export(isolator, input, parent);

        // then
        assertThat(result.isSuccess()).isTrue();
        assertThat(leaves).hasSize(2).doesNotContain(parent);
        assertThat(leaves.getFirst()).isNotSameAs(leaves.getLast());
        assertThat(parent.aggregateResult()).isEqualTo(new ExportResponseObservation.AggregateResult(0, 1, 0, 1, 1));
        assertThat(ExportResponseScope.current()).isNull();
    }

    @Test
    void asynchronousCompletionPublishesOnlyAfterTheLastLeafAndRestoresScope() throws InterruptedException {
        // given
        CompletableResultCode first = new CompletableResultCode();
        CompletableResultCode second = new CompletableResultCode();
        AtomicInteger calls = new AtomicInteger();
        OtlpRequestSplittingSpanExporter isolator = isolator(exporter(spans ->
                calls.incrementAndGet() == 1 ? first : second));
        ExportResponseObservation parent = new ExportResponseObservation(2);
        CompletableResultCode result = export(isolator,
                List.of(span("a".repeat(800)), span("b".repeat(800))), parent);
        AtomicInteger completions = new AtomicInteger();
        result.whenComplete(completions::incrementAndGet);
        ExportResponseObservation unrelated = new ExportResponseObservation(0);
        AtomicBoolean restored = new AtomicBoolean();

        // when
        Thread completionThread = new Thread(() -> {
            try (ExportResponseScope.Handle ignored = ExportResponseScope.open(unrelated)) {
                first.succeed();
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
        second.succeed();
        assertThat(result.isSuccess()).isTrue();
        assertThat(completions).hasValue(1);
        assertThat(parent.aggregateResult()).isEqualTo(new ExportResponseObservation.AggregateResult(2, 0, 0, 0, 0));
    }

    @Test
    void completedResultsDoNotGrowTheStackWithLeafCount() {
        // given
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger minimumDepth = new AtomicInteger(Integer.MAX_VALUE);
        AtomicInteger maximumDepth = new AtomicInteger();
        OtlpRequestSplittingSpanExporter isolator = isolator(exporter(spans -> {
            calls.incrementAndGet();
            int depth = Thread.currentThread().getStackTrace().length;
            minimumDepth.accumulateAndGet(depth, Math::min);
            maximumDepth.accumulateAndGet(depth, Math::max);
            return new CompletableResultCode().succeed();
        }));
        SpanData single = span("a".repeat(800));
        List<SpanData> input = Collections.nCopies(1024, single);
        ExportResponseObservation parent = new ExportResponseObservation(input.size());

        // when
        CompletableResultCode result = export(isolator, input, parent);

        // then
        assertThat(result.isSuccess()).isTrue();
        assertThat(calls).hasValue(1024);
        assertThat(maximumDepth.get() - minimumDepth.get()).isLessThan(8);
        assertThat(parent.aggregateResult().accepted()).isEqualTo(1024);
    }

    @Test
    void deadlineAfterCompletedLeafStopsFurtherAdmission() {
        // given
        AtomicLong clock = new AtomicLong();
        CompletableResultCode first = new CompletableResultCode();
        AtomicInteger calls = new AtomicInteger();
        OtlpRequestSplittingSpanExporter isolator = new OtlpRequestSplittingSpanExporter(exporter(spans -> {
            calls.incrementAndGet();
            return first;
        }), 1000, 10, clock::get);
        ExportResponseObservation parent = new ExportResponseObservation(2);
        CompletableResultCode result = export(isolator,
                List.of(span("a".repeat(800)), span("b".repeat(800))), parent);

        // when
        clock.set(10);
        first.succeed();

        // then
        assertThat(result.isDone()).isTrue();
        assertThat(result.isSuccess()).isFalse();
        assertThat(calls).hasValue(1);
        assertThat(parent.aggregateResult()).isEqualTo(new ExportResponseObservation.AggregateResult(1, 0, 1, 0, 0));
    }

    @Test
    void deadlineDuringMeasurementPreventsDelegateInvocation() {
        // given
        AtomicLong clock = new AtomicLong();
        AtomicInteger calls = new AtomicInteger();
        SpanData delayed = new DelegatingSpanData(span("small")) {
            @Override
            public String getName() {
                clock.set(10);
                return super.getName();
            }
        };
        OtlpRequestSplittingSpanExporter isolator = new OtlpRequestSplittingSpanExporter(exporter(spans -> {
            calls.incrementAndGet();
            return CompletableResultCode.ofSuccess();
        }), 1000, 10, clock::get);

        // when
        CompletableResultCode result = isolator.export(List.of(delayed));

        // then
        assertThat(result.isSuccess()).isFalse();
        assertThat(calls).hasValue(0);
    }

    @Test
    void measurementFailureAfterSuccessPreservesCompletedCounts() {
        // given
        AtomicBoolean failing = new AtomicBoolean();
        CompletableResultCode first = new CompletableResultCode();
        AtomicInteger calls = new AtomicInteger();
        SpanData broken = new DelegatingSpanData(span("b".repeat(800))) {
            @Override
            public String getName() {
                if (failing.get()) {
                    throw new IllegalStateException("Simulated measurement failure");
                }
                return super.getName();
            }
        };
        OtlpRequestSplittingSpanExporter isolator = isolator(exporter(spans -> {
            calls.incrementAndGet();
            return first;
        }));
        ExportResponseObservation parent = new ExportResponseObservation(2);
        CompletableResultCode result = export(isolator, List.of(span("a".repeat(800)), broken), parent);
        result.whenComplete(() -> {
            throw new IllegalStateException("Simulated completion observer failure");
        });

        // when
        failing.set(true);
        assertThatCode(first::succeed).doesNotThrowAnyException();

        // then
        assertThat(result.isSuccess()).isFalse();
        assertThat(calls).hasValue(1);
        assertThat(parent.aggregateResult()).isEqualTo(new ExportResponseObservation.AggregateResult(1, 0, 1, 0, 0));
        assertThat(ExportResponseScope.current()).isNull();
    }

    @Test
    void fatalMeasurementCompletesOuterResultBeforeRethrowing() {
        // given
        AtomicBoolean failing = new AtomicBoolean();
        CompletableResultCode first = new CompletableResultCode();
        SpanData broken = new DelegatingSpanData(span("b".repeat(800))) {
            @Override
            public String getName() {
                if (failing.get()) {
                    throw new LinkageError("Simulated measurement linkage failure");
                }
                return super.getName();
            }
        };
        ExportResponseObservation parent = new ExportResponseObservation(2);
        CompletableResultCode result = export(isolator(exporter(spans -> first)),
                List.of(span("a".repeat(800)), broken), parent);

        // when
        failing.set(true);

        // then
        assertThatThrownBy(first::succeed).isInstanceOf(LinkageError.class);
        assertThat(result.isDone()).isTrue();
        assertThat(result.isSuccess()).isFalse();
        assertThat(parent.aggregateResult()).isEqualTo(new ExportResponseObservation.AggregateResult(1, 0, 1, 0, 0));
        assertThat(ExportResponseScope.current()).isNull();
    }

    @Test
    void shapeTruncationIsCountedOnceBeforeIsolationSplitsTheBatch() {
        // given
        List<SpanData> sent = new ArrayList<>();
        SpanExporter delegate = exporter(spans -> {
            sent.addAll(spans);
            return CompletableResultCode.ofSuccess();
        });
        OtlpRequestSplittingSpanExporter isolator = new OtlpRequestSplittingSpanExporter(
                delegate, 2000, TimeUnit.SECONDS.toNanos(30), () -> 0L);
        SafeSpanExporter safe = new SafeSpanExporter(isolator,
                TracingSettingsSourceTestFixtures.boundDefaults(AgentInstanceId.random()),
                ScrubbingRuntimeTestFixtures.disabled());
        Span large = provider.get("isolation-test").spanBuilder("oversized").startSpan();
        large.setAttribute(AttributeKey.stringArrayKey("payload"), Collections.nCopies(8, "x".repeat(1000)));
        large.end();
        List<SpanData> input = List.of(span("a".repeat(2000)), span("b".repeat(800)),
                ((ReadableSpan) large).toSpanData());

        // when
        CompletableResultCode result = safe.export(input);

        // then
        assertThat(result.isSuccess()).isTrue();
        assertThat(sent).hasSize(2);
        assertThat(sent.getFirst().getName()).hasSize(1024);
        assertThat(safe.getShapeTruncationsTotal()).isEqualTo(1);
        assertThat(safe.getOversizedSpansDropped()).isEqualTo(1);
        assertThat(safe.getExportedSpans()).isEqualTo(2);
        assertThat(safe.getDroppedSpans()).isZero();
        assertThat(safe.getExportFailures()).isZero();
    }

    @Test
    void shutdownDuringRecoveryStopsAtTheNextDelegateFailure() {
        // given
        AtomicBoolean shutdown = new AtomicBoolean();
        AtomicInteger calls = new AtomicInteger();
        CompletableResultCode first = new CompletableResultCode();
        SpanExporter delegate = new SpanExporter() {
            @Override
            public CompletableResultCode export(Collection<SpanData> spans) {
                calls.incrementAndGet();
                return shutdown.get() ? CompletableResultCode.ofFailure() : first;
            }

            @Override
            public CompletableResultCode flush() {
                return CompletableResultCode.ofSuccess();
            }

            @Override
            public CompletableResultCode shutdown() {
                shutdown.set(true);
                return CompletableResultCode.ofSuccess();
            }
        };
        OtlpRequestSplittingSpanExporter isolator = isolator(delegate);
        ExportResponseObservation parent = new ExportResponseObservation(3);
        CompletableResultCode result = export(isolator,
                List.of(span("a".repeat(800)), span("b".repeat(800)), span("c".repeat(800))), parent);

        // when
        isolator.shutdown();
        first.succeed();

        // then
        assertThat(result.isSuccess()).isFalse();
        assertThat(calls).hasValue(2);
        assertThat(parent.aggregateResult()).isEqualTo(new ExportResponseObservation.AggregateResult(1, 0, 2, 0, 0));
    }

    @Test
    void fatalContinuationReleasesWorkerSlotForTheNextBatch() throws InterruptedException {
        // given
        CompletableResultCode first = new CompletableResultCode();
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch nextBatchEntered = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        OtlpRequestSplittingSpanExporter isolator = isolator(exporter(spans -> {
            int call = calls.incrementAndGet();
            if (call == 1) {
                firstEntered.countDown();
                return first;
            }
            if (call == 2) {
                throw new LinkageError("Simulated continuation linkage failure");
            }
            nextBatchEntered.countDown();
            return CompletableResultCode.ofSuccess();
        }));
        PlatformDropOldestExportSpanProcessor processor = PlatformDropOldestExportSpanProcessor.builder(isolator)
                .maxQueueSize(8).maxExportBatchSize(2).scheduleDelay(Duration.ofHours(1))
                .exportTimeout(Duration.ofMillis(10)).shutdownTimeout(Duration.ofSeconds(2)).build();
        try (SdkTracerProvider tracing = SdkTracerProvider.builder()
                .setResource(Resource.empty()).addSpanProcessor(processor).build()) {
            tracing.get("worker-test").spanBuilder("a".repeat(800)).startSpan().end();
            tracing.get("worker-test").spanBuilder("b".repeat(800)).startSpan().end();
            assertThat(firstEntered.await(3, TimeUnit.SECONDS)).isTrue();
            await().atMost(Duration.ofSeconds(3)).until(() -> processor.metricsSnapshot().timeoutsTotal() > 0);
            tracing.get("worker-test").spanBuilder("next").startSpan().end();
            processor.forceFlush();

            // when
            assertThatThrownBy(first::succeed).isInstanceOf(LinkageError.class);

            // then
            assertThat(nextBatchEntered.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(calls).hasValue(3);
            assertThat(processor.metricsSnapshot().lifecycleState()).isEqualTo("ACTIVE");
        } finally {
            first.fail();
            processor.shutdown().join(3, TimeUnit.SECONDS);
        }
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
        OtlpRequestSplittingSpanExporter isolator = isolator(delegate);

        // when
        isolator.flush();
        isolator.shutdown();

        // then
        assertThat(flushed).hasValue(1);
        assertThat(closed).hasValue(1);
    }

    private OtlpRequestSplittingSpanExporter isolator(SpanExporter delegate) {
        return new OtlpRequestSplittingSpanExporter(delegate, 1000, TimeUnit.SECONDS.toNanos(30), () -> 0L);
    }

    private SpanData span(String name) {
        Span span = provider.get("isolation-test").spanBuilder(name).startSpan();
        span.end();
        return ((ReadableSpan) span).toSpanData();
    }

    private static CompletableResultCode export(SpanExporter exporter,
                                                 List<SpanData> spans,
                                                 ExportResponseObservation observation) {
        try (ExportResponseScope.Handle ignored = ExportResponseScope.open(observation)) {
            return exporter.export(spans);
        }
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
