package space.br1440.platform.tracing.otel.javaagent.exporter.guard;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSourceTestFixtures;
import space.br1440.platform.tracing.otel.javaagent.exporter.SafeSpanExporter;
import space.br1440.platform.tracing.otel.javaagent.exporter.response.ExportResponseScope;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.JmxWireContract;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLogCapture;
import space.br1440.platform.tracing.otel.javaagent.processor.dropoldest.PlatformDropOldestExportSpanProcessor;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime.ScrubbingRuntimeTestFixtures;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;

@Timeout(15)
@ResourceLock(AgentLogCapture.RESOURCE_LOCK)
class OtlpRequestSizeLimitingSpanExporterLifecycleTest {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void lateAggregateAfterTimeoutOrShutdownIsCountedOnce(boolean shutdown) throws InterruptedException {
        // given
        List<CompletableResultCode> pending = new CopyOnWriteArrayList<>();
        CountDownLatch entered = new CountDownLatch(3);
        SpanExporter delegate = new SpanExporter() {
            @Override
            public CompletableResultCode export(Collection<SpanData> spans) {
                CompletableResultCode result = new CompletableResultCode();
                pending.add(result);
                if (pending.size() == 2) {
                    Objects.requireNonNull(ExportResponseScope.current(), "observation").recordRejected(1);
                }
                entered.countDown();
                return result;
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

        SafeSpanExporter safe = new SafeSpanExporter(
                new OtlpRequestSizeLimitingSpanExporter(delegate, 1000),
                TracingSettingsSourceTestFixtures.boundDefaults(AgentInstanceId.random()),
                ScrubbingRuntimeTestFixtures.disabled()
        );
        PlatformDropOldestExportSpanProcessor processor = PlatformDropOldestExportSpanProcessor.builder(safe)
                .maxQueueSize(8).maxExportBatchSize(3).scheduleDelay(Duration.ofHours(1))
                .exportTimeout(Duration.ofMillis(150)).shutdownTimeout(Duration.ofSeconds(2)).build();
        try (SdkTracerProvider provider = SdkTracerProvider.builder()
                .setResource(Resource.empty()).addSpanProcessor(processor).build()) {
            Tracer tracer = provider.get("late-aggregate");
            for (String name : List.of("a", "b", "c")) {
                tracer.spanBuilder(name.repeat(800)).setNoParent().startSpan().end();
            }
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();

            // when
            CompletableResultCode flush = processor.forceFlush().join(3, TimeUnit.SECONDS);
            if (shutdown) {
                assertThat(processor.shutdown().join(3, TimeUnit.SECONDS).isDone()).isTrue();
            }

            // then
            assertThat(flush.isDone()).isTrue();
            assertThat(flush.isSuccess()).isFalse();
            assertThat(processor.metricsSnapshot().timeoutsTotal()).isEqualTo(1);
            assertThat(processor.metricsSnapshot().failuresTotal()).isEqualTo(1);
            assertThat(pending).hasSize(3).allSatisfy(result -> assertThat(result.isDone()).isFalse());
            assertThat(safe.metricsSnapshot().getOrDefault(JmxWireContract.KEY_SAFE_EXPORTER_METRICS_EXPORTED_SPANS, 0L).longValue()).isZero();
            assertThat(safe.metricsSnapshot().getOrDefault(JmxWireContract.KEY_SAFE_EXPORTER_METRICS_REJECTED_SPANS, 0L).longValue()).isZero();
            assertThat(safe.metricsSnapshot().getOrDefault(JmxWireContract.KEY_SAFE_EXPORTER_METRICS_TRANSPORT_DROPPED_SPANS, 0L).longValue()).isZero();
            assertThat(safe.metricsSnapshot().getOrDefault(JmxWireContract.KEY_SAFE_EXPORTER_METRICS_SPLIT_EXPORT_BATCHES, 0L).longValue()).isZero();

            pending.get(2).succeed();
            pending.get(1).succeed();
            assertThat(pending.getFirst().isDone()).isFalse();
            assertThat(safe.metricsSnapshot().getOrDefault(JmxWireContract.KEY_SAFE_EXPORTER_METRICS_EXPORT_BATCHES, 0L).longValue()).isEqualTo(1);
            assertThat(safe.metricsSnapshot().getOrDefault(JmxWireContract.KEY_SAFE_EXPORTER_METRICS_EXPORTED_SPANS, 0L).longValue()).isZero();
            assertThat(safe.metricsSnapshot().getOrDefault(JmxWireContract.KEY_SAFE_EXPORTER_METRICS_REJECTED_SPANS, 0L).longValue()).isZero();
            assertThat(safe.metricsSnapshot().getOrDefault(JmxWireContract.KEY_SAFE_EXPORTER_METRICS_TRANSPORT_DROPPED_SPANS, 0L).longValue()).isZero();
            assertThat(safe.metricsSnapshot().getOrDefault(JmxWireContract.KEY_SAFE_EXPORTER_METRICS_SPLIT_EXPORT_BATCHES, 0L).longValue()).isZero();
            pending.getFirst().fail();
            pending.forEach(CompletableResultCode::succeed);

            assertThat(safe.metricsSnapshot().getOrDefault(JmxWireContract.KEY_SAFE_EXPORTER_METRICS_EXPORT_BATCHES, 0L).longValue()).isEqualTo(1);
            assertThat(safe.metricsSnapshot().getOrDefault(JmxWireContract.KEY_SAFE_EXPORTER_METRICS_EXPORTED_SPANS, 0L).longValue()).isEqualTo(1);
            assertThat(safe.metricsSnapshot().getOrDefault(JmxWireContract.KEY_SAFE_EXPORTER_METRICS_REJECTED_SPANS, 0L).longValue()).isEqualTo(1);
            assertThat(safe.metricsSnapshot().getOrDefault(JmxWireContract.KEY_SAFE_EXPORTER_METRICS_TRANSPORT_DROPPED_SPANS, 0L).longValue()).isEqualTo(1);
            assertThat(safe.metricsSnapshot().getOrDefault(JmxWireContract.KEY_SAFE_EXPORTER_METRICS_EXPORT_BATCH_FAILURES, 0L).longValue()).isEqualTo(1);
            assertThat(safe.metricsSnapshot().getOrDefault(JmxWireContract.KEY_SAFE_EXPORTER_METRICS_SPLIT_EXPORT_BATCHES, 0L).longValue()).isEqualTo(1);
            assertThat(safe.metricsSnapshot().getOrDefault(JmxWireContract.KEY_SAFE_EXPORTER_METRICS_OVERSIZED_SPANS_DROPPED, 0L).longValue()).isZero();
            assertThat(safe.metricsSnapshot().getOrDefault(JmxWireContract.KEY_SAFE_EXPORTER_METRICS_SIZE_MEASUREMENT_FAILURES, 0L).longValue()).isZero();
            assertThat(processor.metricsSnapshot().timeoutsTotal()).isEqualTo(1);
            assertThat(processor.metricsSnapshot().failuresTotal()).isEqualTo(1);
        } finally {
            pending.forEach(CompletableResultCode::fail);
        }
    }

    @Test
    void lateLastLeafCreditsTheAggregateOnceAfterWorkerTimeoutAndReleasesTheNextBatch() throws InterruptedException {
        // given
        CompletableResultCode pending = new CompletableResultCode();
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch nextEntered = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        List<String> delivered = new CopyOnWriteArrayList<>();
        SpanExporter delegate = new SpanExporter() {
            @Override
            public CompletableResultCode export(Collection<SpanData> spans) {
                spans.forEach(span -> delivered.add(span.getName()));
                if (calls.incrementAndGet() == 1) {
                    firstEntered.countDown();
                    return pending;
                }
                nextEntered.countDown();
                return CompletableResultCode.ofSuccess();
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
        Duration timeout = Duration.ofMillis(150);
        SafeSpanExporter safe = new SafeSpanExporter(
                new OtlpRequestSizeLimitingSpanExporter(delegate, 1000),
                TracingSettingsSourceTestFixtures.boundDefaults(AgentInstanceId.random()),
                ScrubbingRuntimeTestFixtures.disabled());
        PlatformDropOldestExportSpanProcessor processor = PlatformDropOldestExportSpanProcessor.builder(safe)
                .maxQueueSize(8)
                .maxExportBatchSize(2)
                .scheduleDelay(Duration.ofHours(1))
                .exportTimeout(timeout)
                .shutdownTimeout(Duration.ofSeconds(2))
                .build();
        try (SdkTracerProvider provider = SdkTracerProvider.builder()
                .setResource(Resource.empty())
                .addSpanProcessor(processor)
                .build()) {
            Tracer tracer = provider.get("late-isolation");
            Span oversized = tracer.spanBuilder("oversized").setNoParent().startSpan();
            oversized.setAttribute("payload", "x".repeat(4000));
            oversized.end();
            String acceptedName = "a".repeat(800);
            tracer.spanBuilder(acceptedName).setNoParent().startSpan().end();
            assertThat(firstEntered.await(3, TimeUnit.SECONDS)).isTrue();

            // when
            CompletableResultCode timedOutFlush = processor.forceFlush().join(3, TimeUnit.SECONDS);

            // then
            assertThat(timedOutFlush.isDone()).isTrue();
            assertThat(timedOutFlush.isSuccess()).isFalse();
            assertThat(processor.metricsSnapshot().timeoutsTotal()).isEqualTo(1);
            assertThat(processor.metricsSnapshot().failuresTotal()).isEqualTo(1);
            assertThat(safe.metricsSnapshot().getOrDefault(JmxWireContract.KEY_SAFE_EXPORTER_METRICS_EXPORTED_SPANS, 0L).longValue()).isZero();
            assertThat(safe.metricsSnapshot().getOrDefault(JmxWireContract.KEY_SAFE_EXPORTER_METRICS_OVERSIZED_SPANS_DROPPED, 0L).longValue()).isZero();

            pending.succeed();
            pending.succeed();

            assertThat(safe.metricsSnapshot().getOrDefault(JmxWireContract.KEY_SAFE_EXPORTER_METRICS_EXPORT_BATCHES, 0L).longValue()).isEqualTo(1);
            assertThat(safe.metricsSnapshot().getOrDefault(JmxWireContract.KEY_SAFE_EXPORTER_METRICS_EXPORTED_SPANS, 0L).longValue()).isEqualTo(1);
            assertThat(safe.metricsSnapshot().getOrDefault(JmxWireContract.KEY_SAFE_EXPORTER_METRICS_OVERSIZED_SPANS_DROPPED, 0L).longValue()).isEqualTo(1);
            assertThat(safe.metricsSnapshot().getOrDefault(JmxWireContract.KEY_SAFE_EXPORTER_METRICS_EXPORT_BATCH_FAILURES, 0L).longValue()).isZero();
            assertThat(safe.metricsSnapshot().getOrDefault(JmxWireContract.KEY_SAFE_EXPORTER_METRICS_TRANSPORT_DROPPED_SPANS, 0L).longValue()).isZero();
            assertThat(processor.metricsSnapshot().timeoutsTotal()).isEqualTo(1);
            assertThat(processor.metricsSnapshot().failuresTotal()).isEqualTo(1);
            assertThat(timedOutFlush.isSuccess()).isFalse();

            tracer.spanBuilder("next").setNoParent().startSpan().end();
            assertThat(processor.forceFlush().join(3, TimeUnit.SECONDS).isSuccess()).isTrue();
            assertThat(nextEntered.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(calls).hasValue(2);
            assertThat(delivered).containsExactly(acceptedName, "next");
            assertThat(safe.metricsSnapshot().getOrDefault(JmxWireContract.KEY_SAFE_EXPORTER_METRICS_EXPORTED_SPANS, 0L).longValue()).isEqualTo(2);
            assertThat(safe.metricsSnapshot().getOrDefault(JmxWireContract.KEY_SAFE_EXPORTER_METRICS_OVERSIZED_SPANS_DROPPED, 0L).longValue()).isEqualTo(1);
            assertThat(processor.metricsSnapshot().lifecycleState()).isEqualTo("ACTIVE");
        } finally {
            pending.fail();
        }
    }
}
