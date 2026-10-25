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
import space.br1440.platform.tracing.api.span.spec.SpanHandle;
import space.br1440.platform.tracing.autoconfigure.TracingRuntimeAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.control.channel.TracingControlChannelAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.support.ControlledAgentTestRuntime;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;

class MeteredMetricsCountTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    TracingControlChannelAutoConfiguration.class,
                    TracingRuntimeAutoConfiguration.class,
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
    void recordException_incrementsExceptionsRecorded() {
        contextRunner.run(context -> {
            // given
            MeterRegistry registry = context.getBean(MeterRegistry.class);
            TraceOperations tracing = context.getBean(TraceOperations.class);

            // when
            SpanHandle handle = tracing.spans().operation("failing-op").start();
            handle.recordException(new IllegalStateException("boom"));
            handle.close();

            // then
            assertThat(registry.get(PlatformTracingMetrics.EXCEPTIONS_RECORDED)
                    .counter()
                    .count()).isEqualTo(1.0);

            assertThat(registry.get(PlatformTracingMetrics.SPANS_STARTED)
                    .tag("category", SpanCategory.INTERNAL.value())
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
