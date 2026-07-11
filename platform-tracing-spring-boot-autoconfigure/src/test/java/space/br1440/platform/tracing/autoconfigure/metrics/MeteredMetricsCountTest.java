package space.br1440.platform.tracing.autoconfigure.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import space.br1440.platform.tracing.api.PlatformTracing;
import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.api.span.SpanLinkContext;
import space.br1440.platform.tracing.autoconfigure.TracingCoreAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.TracingMetricsAutoConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Slice 6 hard gate: metered decorator increments bounded self-metrics without masking SpanRelationship.
 */
class MeteredMetricsCountTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    TracingCoreAutoConfiguration.class,
                    TracingMetricsAutoConfiguration.class))
            .withUserConfiguration(
                    MetricsCountTestConfiguration.class,
                    MeterRegistryConfiguration.class);

    @Test
    void startSpan_incrementsSpansStartedByCategory() {
        contextRunner.run(context -> {
            MeterRegistry registry = context.getBean(MeterRegistry.class);
            PlatformTracing tracing = context.getBean(PlatformTracing.class);
            InMemorySpanExporter exporter = context.getBean(InMemorySpanExporter.class);

            tracing.manual().operation("op-a").start().close();
            tracing.manual().operation("op-b").root().start().close();
            tracing.manual().transport().kafka().consumer()
                    .batch("orders")
                    .root()
                    .linkedTo(SpanLinkContext.sampled(
                            "0102030405060708090a0b0c0d0e0f10", "0102030405060708"))
                    .start()
                    .close();

            assertThat(registry.find(PlatformTracingMetrics.SPANS_STARTED)
                    .tag("category", SpanCategory.INTERNAL.value())
                    .counter()
                    .count()).isEqualTo(2.0);
            assertThat(registry.find(PlatformTracingMetrics.SPANS_STARTED)
                    .tag("category", SpanCategory.KAFKA_CONSUMER.value())
                    .counter()
                    .count()).isEqualTo(1.0);
            assertThat(exporter.getFinishedSpanItems()).hasSize(3);
        });
    }

    @Test
    void recordException_incrementsExceptionsRecorded() {
        contextRunner.run(context -> {
            MeterRegistry registry = context.getBean(MeterRegistry.class);
            PlatformTracing tracing = context.getBean(PlatformTracing.class);

            var handle = tracing.manual().operation("failing-op").start();
            handle.recordException(new IllegalStateException("boom"));
            handle.close();

            assertThat(registry.find(PlatformTracingMetrics.EXCEPTIONS_RECORDED)
                    .counter()
                    .count()).isEqualTo(1.0);
            assertThat(registry.find(PlatformTracingMetrics.SPANS_STARTED)
                    .tag("category", SpanCategory.INTERNAL.value())
                    .counter()
                    .count()).isEqualTo(1.0);
        });
    }

    @Test
    void spansStartedMetric_usesBoundedCategoryTagsOnly() {
        contextRunner.run(context -> {
            MeterRegistry registry = context.getBean(MeterRegistry.class);
            PlatformTracing tracing = context.getBean(PlatformTracing.class);

            tracing.manual().operation("dynamic-name-" + System.nanoTime()).start().close();

            var counters = registry.find(PlatformTracingMetrics.SPANS_STARTED).counters();
            assertThat(counters).isNotEmpty();
            for (var counter : counters) {
                assertThat(counter.getId().getTags()).hasSize(1);
                assertThat(counter.getId().getTags().getFirst().getKey()).isEqualTo("category");
            }
        });
    }

    @Configuration
    static class MetricsCountTestConfiguration {

        @Bean
        InMemorySpanExporter spanExporter() {
            return InMemorySpanExporter.create();
        }

        @Bean
        OpenTelemetry openTelemetry(InMemorySpanExporter spanExporter) {
            SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                    .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
                    .build();
            return OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build();
        }
    }

    @Configuration
    static class MeterRegistryConfiguration {
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }
}
