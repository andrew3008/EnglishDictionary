package space.br1440.platform.tracing.otel.bsp;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;

@Timeout(15)
class BatchSpanProcessorOverflowPolicyProbeTest {

    @Test
    void upstreamDropsIncomingSpansWhenQueueIsFull() throws InterruptedException {
        // given
        ControlledExporter exporter = new ControlledExporter();
        try (SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(BatchSpanProcessor.builder(exporter)
                        .setMaxQueueSize(4)
                        .setMaxExportBatchSize(2)
                        .setScheduleDelay(Duration.ofMinutes(10))
                        .setExporterTimeout(Duration.ofSeconds(30))
                        .build())
                .build()) {
            Tracer tracer = provider.get("upstream-overflow");
            try {
                emit(tracer, 0);
                emit(tracer, 1);
                assertThat(exporter.firstStarted.await(3, TimeUnit.SECONDS)).isTrue();

                // when
                for (int sequence = 2; sequence < 34; sequence++) {
                    emit(tracer, sequence);
                }
                CompletableResultCode flush = provider.forceFlush();
                exporter.first.succeed();

                // then
                assertThat(flush.join(3, TimeUnit.SECONDS).isSuccess()).isTrue();
                assertThat(exporter.names).containsExactly("0", "1", "2", "3", "4", "5");
            } finally {
                exporter.first.succeed();
            }
        }
    }

    private static void emit(Tracer tracer, int sequence) {
        tracer.spanBuilder(Integer.toString(sequence)).startSpan().end();
    }

    private static final class ControlledExporter implements SpanExporter {
        private final CountDownLatch firstStarted = new CountDownLatch(1);
        private final CompletableResultCode first = new CompletableResultCode();
        private final List<String> names = new CopyOnWriteArrayList<>();

        @Override
        public CompletableResultCode export(Collection<SpanData> spans) {
            spans.forEach(span -> names.add(span.getName()));
            if (firstStarted.getCount() != 0) {
                firstStarted.countDown();
                return first;
            }

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
    }
}
