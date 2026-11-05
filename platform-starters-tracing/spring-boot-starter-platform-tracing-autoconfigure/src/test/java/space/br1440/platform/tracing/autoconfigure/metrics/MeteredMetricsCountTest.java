package space.br1440.platform.tracing.autoconfigure.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collection;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.api.span.RemoteSpanLink;
import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.api.span.spec.DatabaseSpanHandle;
import space.br1440.platform.tracing.api.span.spec.SpanHandle;
import space.br1440.platform.tracing.autoconfigure.TracingRuntimeAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.control.channel.TracingControlChannelAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.diagnostics.TracingRuntimeDiagnosticsAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.support.ControlledAgentTestRuntime;
import space.br1440.platform.tracing.otel.semconv.SemconvKeys;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;

class MeteredMetricsCountTest {

    private static final String REMOVED_EXCEPTION_COUNTER = "platform_tracing_exceptions_recorded_total";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    TracingControlChannelAutoConfiguration.class,
                    TracingRuntimeAutoConfiguration.class,
                    TracingRuntimeDiagnosticsAutoConfiguration.class,
                    TracingMetricsAutoConfiguration.class)
            )
            .withPropertyValues("platform.tracing.control.channel=NONE")
            .withInitializer(ControlledAgentTestRuntime::initializeWithExporter)
            .withUserConfiguration(MeterRegistryConfiguration.class);

    @Test
    void startSpan_incrementsSpansStartedByCategory() {
        contextRunner.run(context -> {
            // given
            MeterRegistry registry = context.getBean(MeterRegistry.class);
            TraceOperations tracing = context.getBean(TraceOperations.class);
            InMemorySpanExporter exporter = context.getBean(InMemorySpanExporter.class);

            // when
            tracing.spans().operation("op-a").start().close();
            tracing.spans().operation("op-b").root().start().close();
            tracing.spans().transport().kafka().consumer()
                    .batch("orders")
                    .root()
                    .linkedTo(RemoteSpanLink.sampled(
                            "0102030405060708090a0b0c0d0e0f10", "0102030405060708"))
                    .start()
                    .close();

            // then
            assertThat(registry.get(PlatformTracingMetrics.SPANS_STARTED)
                    .tag("category", SpanCategory.INTERNAL.value())
                    .counter()
                    .count()).isEqualTo(2.0);

            assertThat(registry.get(PlatformTracingMetrics.SPANS_STARTED)
                    .tag("category", SpanCategory.KAFKA_CONSUMER.value())
                    .counter()
                    .count()).isEqualTo(1.0);

            assertThat(exporter.getFinishedSpanItems()).hasSize(3);
        });
    }

    @Test
    void recordException_reachesRealHandleWithoutExceptionCounter() {
        contextRunner.run(context -> {
            // given
            MeterRegistry registry = context.getBean(MeterRegistry.class);
            TraceOperations tracing = context.getBean(TraceOperations.class);
            InMemorySpanExporter exporter = context.getBean(InMemorySpanExporter.class);
            IllegalStateException failure = new IllegalStateException("boom");

            // when
            SpanHandle handle = tracing.spans().operation("failing-op").start();
            handle.recordException(failure);
            handle.recordException(failure);
            handle.close();

            // then
            SpanData span = exporter.getFinishedSpanItems().getFirst();
            assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
            assertThat(span.getEvents()).hasSize(1);
            assertThat(registry.find(REMOVED_EXCEPTION_COUNTER).meters()).isEmpty();
            assertThat(registry.get(PlatformTracingMetrics.SPANS_STARTED)
                    .tag("category", SpanCategory.INTERNAL.value())
                    .counter()
                    .count()).isEqualTo(1.0);
        });
    }

    @Test
    void databaseHandle_reachesConsumerWithoutWrapper() {
        contextRunner.run(context -> {
            // given
            MeterRegistry registry = context.getBean(MeterRegistry.class);
            TraceOperations tracing = context.getBean(TraceOperations.class);
            InMemorySpanExporter exporter = context.getBean(InMemorySpanExporter.class);

            // when
            DatabaseSpanHandle handle = tracing.spans().transport().database()
                    .system("postgresql")
                    .operation("SELECT")
                    .collection("orders")
                    .start();
            handle.responseStatusCode("23505");
            handle.recordException(new IllegalStateException("duplicate key"));
            handle.close();

            // then
            SpanData span = exporter.getFinishedSpanItems().getFirst();
            assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
            assertThat(span.getAttributes().get(SemconvKeys.ERROR_TYPE)).isEqualTo("23505");
            assertThat(span.getEvents()).hasSize(1);
            assertThat(registry.find(REMOVED_EXCEPTION_COUNTER).meters()).isEmpty();
            assertThat(registry.get(PlatformTracingMetrics.SPANS_STARTED)
                    .tag("category", SpanCategory.DATABASE.value())
                    .counter()
                    .count()).isEqualTo(1.0);
        });
    }

    @Test
    void spansStartedMetric_usesBoundedCategoryTagsOnly() {
        contextRunner.run(context -> {
            // given
            MeterRegistry registry = context.getBean(MeterRegistry.class);
            TraceOperations tracing = context.getBean(TraceOperations.class);

            // when
            tracing.spans().operation("dynamic-name-" + System.nanoTime()).start().close();

            // then
            Collection<Counter> counters = registry.find(PlatformTracingMetrics.SPANS_STARTED).counters();
            assertThat(counters).isNotEmpty();
            for (Counter counter : counters) {
                assertThat(counter.getId().getTags()).hasSize(1);
                assertThat(counter.getId().getTags().getFirst().getKey()).isEqualTo("category");
            }
        });
    }

    @Configuration
    static class MeterRegistryConfiguration {

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }
}
