package space.br1440.platform.tracing.otel.javaagent.processor.dropoldest;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.sdk.trace.samplers.Sampler;

class ExportWorkerShutdownOwnershipTest {

    private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(3);

    @Test
    void shutdownIsIdempotentAndClosesTheExporterOnce() throws InterruptedException {
        // given
        BlockingShutdownExporter exporter = new BlockingShutdownExporter();
        try (PlatformDropOldestExportSpanProcessor processor = PlatformDropOldestExportSpanProcessor.builder(exporter)
                .scheduleDelay(Duration.ofMinutes(1))
                .shutdownTimeout(Duration.ofSeconds(2))
                .build()) {
            // when
            CompletableResultCode first = processor.shutdown();
            assertThat(exporter.awaitShutdownEntered()).isTrue();
            for (int invocation = 0; invocation < 8; invocation++) {
                assertThat(processor.shutdown()).isSameAs(first);
            }

            // then
            assertThat(exporter.shutdownCalls()).isEqualTo(1);
            assertThat(threadsNamed("platform-tracing-drop-oldest-shutdown")).isEmpty();
            exporter.releaseShutdown();
            assertThat(first.join(AWAIT_TIMEOUT.toNanos(), TimeUnit.NANOSECONDS).isSuccess()).isTrue();
            assertThat(exporter.shutdownCalls()).isEqualTo(1);
        }
    }

    @Test
    void shutdownDuringExportCompletesWithinTheDeclaredBound() throws InterruptedException {
        // given
        HeldExportExporter exporter = new HeldExportExporter();
        PlatformDropOldestExportSpanProcessor processor = PlatformDropOldestExportSpanProcessor.builder(exporter)
                .maxQueueSize(8)
                .maxExportBatchSize(1)
                .scheduleDelay(Duration.ofMinutes(10))
                .exportTimeout(Duration.ofMillis(400))
                .shutdownTimeout(Duration.ofMillis(200))
                .build();

        try (SdkTracerProvider provider = SdkTracerProvider.builder()
                .setSampler(Sampler.alwaysOn())
                .addSpanProcessor(processor)
                .build()) {
            provider.get("held-export").spanBuilder("span").startSpan().end();
            assertThat(exporter.awaitExportEntered()).isTrue();
            for (int index = 0; index < 3; index++) {
                provider.get("held-export").spanBuilder("queued-" + index).startSpan().end();
            }
            assertThat(processor.metricsSnapshot().queueSize()).isEqualTo(3);

            // when
            CompletableResultCode shutdown = processor.shutdown();

            // then
            assertThat(shutdown.join(3, TimeUnit.SECONDS).isDone()).isTrue();
            assertThat(shutdown.isSuccess()).isFalse();
            assertThat(exporter.shutdownCalls()).isEqualTo(1);
            assertThat(processor.metricsSnapshot().discardedQueuedTotal()).isEqualTo(3);
        } finally {
            exporter.releaseExport();
        }
    }

    @Test
    void exporterShutdownThatNeverCompletesFailsWithinItsOwnTimeout() {
        // given
        NeverCompletingShutdownExporter exporter = new NeverCompletingShutdownExporter();
        PlatformDropOldestExportSpanProcessor processor = PlatformDropOldestExportSpanProcessor.builder(exporter)
                .scheduleDelay(Duration.ofMinutes(1))
                .shutdownTimeout(Duration.ofMillis(200))
                .build();

        // when
        CompletableResultCode shutdown = processor.shutdown();

        // then
        assertThat(shutdown.join(3, TimeUnit.SECONDS).isDone()).isTrue();
        assertThat(shutdown.isSuccess()).isFalse();
    }

    @SuppressWarnings("SameParameterValue")
    private static Set<Thread> threadsNamed(String prefix) {
        Set<Thread> matching = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread.getName().startsWith(prefix)) {
                matching.add(thread);
            }
        }

        return matching;
    }

    private static final class BlockingShutdownExporter implements SpanExporter {
        private final CountDownLatch shutdownEntered = new CountDownLatch(1);
        private final CountDownLatch shutdownRelease = new CountDownLatch(1);
        private final AtomicInteger shutdownCalls = new AtomicInteger();

        @Override
        public CompletableResultCode export(Collection<SpanData> spans) {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            shutdownCalls.incrementAndGet();
            shutdownEntered.countDown();
            try {
                if (!shutdownRelease.await(AWAIT_TIMEOUT.toNanos(), TimeUnit.NANOSECONDS)) {
                    return CompletableResultCode.ofFailure();
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return CompletableResultCode.ofFailure();
            }

            return CompletableResultCode.ofSuccess();
        }

        boolean awaitShutdownEntered() throws InterruptedException {
            return shutdownEntered.await(AWAIT_TIMEOUT.toNanos(), TimeUnit.NANOSECONDS);
        }

        void releaseShutdown() {
            shutdownRelease.countDown();
        }

        int shutdownCalls() {
            return shutdownCalls.get();
        }
    }

    private static final class HeldExportExporter implements SpanExporter {
        private final CountDownLatch exportEntered = new CountDownLatch(1);
        private final CompletableResultCode exportResult = new CompletableResultCode();
        private final AtomicInteger shutdownCalls = new AtomicInteger();

        @Override
        public CompletableResultCode export(Collection<SpanData> spans) {
            exportEntered.countDown();
            return exportResult;
        }

        @Override
        public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            shutdownCalls.incrementAndGet();
            return CompletableResultCode.ofSuccess();
        }

        boolean awaitExportEntered() throws InterruptedException {
            return exportEntered.await(AWAIT_TIMEOUT.toNanos(), TimeUnit.NANOSECONDS);
        }

        void releaseExport() {
            exportResult.succeed();
        }

        int shutdownCalls() {
            return shutdownCalls.get();
        }
    }

    private static final class NeverCompletingShutdownExporter implements SpanExporter {
        @Override
        public CompletableResultCode export(Collection<SpanData> spans) {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            return new CompletableResultCode();
        }
    }
}
