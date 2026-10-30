package space.br1440.platform.tracing.otel.javaagent.exporter;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.ResourceLock;

import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSourceTestFixtures;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
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
class OtlpRequestSplittingSpanExporterLifecycleTest {

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
                new OtlpRequestSplittingSpanExporter(delegate, 1000, timeout.toNanos(), () -> 0L),
                TracingSettingsSourceTestFixtures.boundDefaults(AgentInstanceId.random()),
                ScrubbingRuntimeTestFixtures.disabled());
        PlatformDropOldestExportSpanProcessor processor = PlatformDropOldestExportSpanProcessor.builder(safe)
                .maxQueueSize(8)
                .maxExportBatchSize(2)
                .scheduleDelay(Duration.ofHours(1))
                .exportTimeout(timeout)
                .shutdownTimeout(Duration.ofSeconds(2))
                .build();
        SdkTracerProvider provider = SdkTracerProvider.builder()
                .setResource(Resource.empty())
                .addSpanProcessor(processor)
                .build();
        try {
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
            assertThat(safe.getExportedSpans()).isZero();
            assertThat(safe.getOversizedSpansDropped()).isZero();

            pending.succeed();
            pending.succeed();

            assertThat(safe.getExportBatches()).isEqualTo(1);
            assertThat(safe.getExportedSpans()).isEqualTo(1);
            assertThat(safe.getOversizedSpansDropped()).isEqualTo(1);
            assertThat(safe.getExportFailures()).isZero();
            assertThat(safe.getDroppedSpans()).isZero();
            assertThat(processor.metricsSnapshot().timeoutsTotal()).isEqualTo(1);
            assertThat(processor.metricsSnapshot().failuresTotal()).isEqualTo(1);
            assertThat(timedOutFlush.isSuccess()).isFalse();

            tracer.spanBuilder("next").setNoParent().startSpan().end();
            assertThat(processor.forceFlush().join(3, TimeUnit.SECONDS).isSuccess()).isTrue();
            assertThat(nextEntered.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(calls).hasValue(2);
            assertThat(delivered).containsExactly(acceptedName, "next");
            assertThat(safe.getExportedSpans()).isEqualTo(2);
            assertThat(safe.getOversizedSpansDropped()).isEqualTo(1);
            assertThat(processor.metricsSnapshot().lifecycleState()).isEqualTo("ACTIVE");
        } finally {
            pending.fail();
            provider.close();
        }
    }
}
