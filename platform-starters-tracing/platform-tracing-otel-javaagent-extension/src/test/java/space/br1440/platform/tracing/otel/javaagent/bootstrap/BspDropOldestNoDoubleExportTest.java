package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSourceTestFixtures;
import space.br1440.platform.tracing.otel.javaagent.factory.PlatformExportProcessorFactory;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.jmx.registration.PlatformTracingMBeanRegistrar;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime.ScrubbingRuntimeTestFixtures;

import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.autoconfigure.spi.internal.DefaultConfigProperties;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;

class BspDropOldestNoDoubleExportTest {

    @Test
    @DisplayName("каждый span экспортируется ровно один раз")
    void everySpanExportedExactlyOnce() {
        // given
        CountingExporter counting = new CountingExporter();

        PlatformExportProcessorFactory factory = new PlatformExportProcessorFactory(
                new PlatformTracingMBeanRegistrar(AgentInstanceId.random()),
                ScrubbingRuntimeTestFixtures.disabled()
        );

        SpanExporter wrapped = factory.captureExporter(
                counting,
                TracingSettingsSourceTestFixtures.boundDefaults(AgentInstanceId.random())
        );

        SpanProcessor processor = factory.maybeReplaceExportProcessor(
                BatchSpanProcessor.builder(wrapped).build(),
                DefaultConfigProperties.createFromMap(Map.of("otel.bsp.schedule.delay", "100"))
        );

        // when
        int totalSpans = 50;
        try (SdkTracerProvider provider = SdkTracerProvider.builder().addSpanProcessor(processor).build()) {
            Tracer tracer = provider.get("no-double-export-test");
            for (int i = 0; i < totalSpans; i++) {
                tracer.spanBuilder("nde-" + i).startSpan().end();
            }

            assertThat(provider.forceFlush().join(5, TimeUnit.SECONDS).isSuccess()).isTrue();
        }

        // then
        Map<String, Long> spanIdCounts = counting.exported.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        s -> s.getSpanContext().getSpanId(),
                        java.util.stream.Collectors.counting()
                ));
        assertThat(spanIdCounts).hasSize(totalSpans);
        spanIdCounts.forEach((spanId, count) ->
                assertThat(count)
                        .as("span %s экспортирован %d раз — ожидается ровно 1", spanId, count)
                        .isEqualTo(1L));
        assertThat(counting.exportCalls.get())
                .as("суммарное число spans в всех export() == totalSpans (точно один проход)")
                .isEqualTo(totalSpans);
    }

    private static final class CountingExporter implements SpanExporter {
        final AtomicInteger exportCalls = new AtomicInteger();
        final List<SpanData> exported = new CopyOnWriteArrayList<>();

        @Override
        public CompletableResultCode export(Collection<SpanData> batch) {
            exportCalls.addAndGet(batch.size());
            exported.addAll(batch);
            return CompletableResultCode.ofSuccess();
        }

        @Override public CompletableResultCode flush() { return CompletableResultCode.ofSuccess(); }
        @Override public CompletableResultCode shutdown() { return CompletableResultCode.ofSuccess(); }
    }
}
