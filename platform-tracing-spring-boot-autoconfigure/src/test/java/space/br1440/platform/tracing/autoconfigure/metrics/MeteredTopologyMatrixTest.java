package space.br1440.platform.tracing.autoconfigure.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import space.br1440.platform.tracing.api.PlatformTracing;
import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.api.span.SpanLinkContext;
import space.br1440.platform.tracing.api.span.spec.SpanSpec;
import space.br1440.platform.tracing.api.span.spec.SpanSpecReason;
import space.br1440.platform.tracing.autoconfigure.TracingCoreAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.TracingMetricsAutoConfiguration;
import space.br1440.platform.tracing.core.facade.DefaultPlatformTracing;
import space.br1440.platform.tracing.core.runtime.TracingRuntime;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Slice 6 hard gate: metered {@link TracingRuntime} preserves topology and links.
 */
class MeteredTopologyMatrixTest {

    private static final String INVALID_SPAN_ID = "0000000000000000";
    private static final String TRACEPARENT =
            "00-0102030405060708090a0b0c0d0e0f10-0102030405060708-01";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    TracingCoreAutoConfiguration.class,
                    TracingMetricsAutoConfiguration.class))
            .withUserConfiguration(
                    MeteredTopologyTestConfiguration.class,
                    MeterRegistryConfiguration.class);

    @Test
    void meteredChain_wrapsDefaultImplementation() {
        contextRunner.run(context -> {
            assertThat(context.getBean(TracingRuntime.class))
                    .isInstanceOf(MeteredTracingRuntime.class);
            assertThat(context.getBean(PlatformTracing.class)).isInstanceOf(DefaultPlatformTracing.class);
        });
    }

    @Test
    void operationRootWithLinks_preservesRootTopologyAndRemoteLinks() {
        contextRunner.run(context -> {
            PlatformTracing tracing = context.getBean(PlatformTracing.class);
            InMemorySpanExporter exporter = context.getBean(InMemorySpanExporter.class);
            SpanLinkContext link = SpanLinkContext.sampled(
                    "0102030405060708090a0b0c0d0e0f10", "0102030405060708");
            tracing.manual().operation("linked-root").root().linkedTo(link).start().close();

            SpanData span = findSpan(exporter, "linked-root");
            assertThat(span.getParentSpanId()).isIn("", INVALID_SPAN_ID);
            assertThat(span.getLinks()).hasSize(1);
            assertThat(span.getLinks().getFirst().getSpanContext().isRemote()).isTrue();
        });
    }

    @Test
    void operationDetached_preservesDetachedNoParentAndNoLinks() {
        contextRunner.run(context -> {
            PlatformTracing tracing = context.getBean(PlatformTracing.class);
            InMemorySpanExporter exporter = context.getBean(InMemorySpanExporter.class);
            try (var parent = tracing.manual().operation("parent").start()) {
                tracing.manual().operation("orphan").detached().start().close();
            }

            SpanData detached = findSpan(exporter, "orphan");
            SpanData parent = findSpan(exporter, "parent");
            assertThat(detached.getParentSpanId()).isIn("", INVALID_SPAN_ID);
            assertThat(detached.getTraceId()).isNotEqualTo(parent.getTraceId());
            assertThat(detached.getLinks()).isEmpty();
        });
    }

    @Test
    void operationDetachedWithLinks_failsFast() {
        contextRunner.run(context -> {
            PlatformTracing tracing = context.getBean(PlatformTracing.class);
            InMemorySpanExporter exporter = context.getBean(InMemorySpanExporter.class);
            SpanLinkContext link = SpanLinkContext.sampled(
                    "0102030405060708090a0b0c0d0e0f10", "0102030405060708");
            assertThatThrownBy(() ->
                    tracing.manual().operation("bad-detached").detached().linkedTo(link).start())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("DETACHED");
            assertThat(exporter.getFinishedSpanItems()).isEmpty();
        });
    }

    @Test
    void kafkaBatchRootWithLinks_preservesRootTopologyAndLinks() {
        contextRunner.run(context -> {
            PlatformTracing tracing = context.getBean(PlatformTracing.class);
            InMemorySpanExporter exporter = context.getBean(InMemorySpanExporter.class);
            tracing.manual().transport().kafka().consumer()
                    .batch("orders")
                    .root()
                    .fromRemoteContext(TRACEPARENT)
                    .start()
                    .close();

            SpanData span = findSpan(exporter, "orders process");
            assertThat(span.getParentSpanId()).isIn("", INVALID_SPAN_ID);
            assertThat(span.getLinks()).hasSize(1);
            assertThat(span.getLinks())
                    .extracting(LinkData::getSpanContext)
                    .extracting(ctx -> ctx.getTraceId() + "/" + ctx.getSpanId())
                    .containsExactly("0102030405060708090a0b0c0d0e0f10/0102030405060708");
        });
    }

    @Test
    void spanFromSpec_rootWithLinks_worksPerPolicy() {
        contextRunner.run(context -> {
            PlatformTracing tracing = context.getBean(PlatformTracing.class);
            InMemorySpanExporter exporter = context.getBean(InMemorySpanExporter.class);
            SpanLinkContext link = SpanLinkContext.sampled(
                    "0102030405060708090a0b0c0d0e0f10", "0102030405060708");
            SpanSpec spec = SpanSpec.builder("spec-root")
                    .category(SpanCategory.INTERNAL)
                    .reason(SpanSpecReason.PLATFORM_EDGE_CASE)
                    .root()
                    .linkedTo(link)
                    .build();
            tracing.manual().spanFromSpec(spec).start().close();

            SpanData span = findSpan(exporter, "spec-root");
            assertThat(span.getParentSpanId()).isIn("", INVALID_SPAN_ID);
            assertThat(span.getLinks()).hasSize(1);
        });
    }

    @Test
    void spanFromSpec_detachedWithoutLinks_worksPerPolicy() {
        contextRunner.run(context -> {
            PlatformTracing tracing = context.getBean(PlatformTracing.class);
            InMemorySpanExporter exporter = context.getBean(InMemorySpanExporter.class);
            SpanSpec spec = SpanSpec.builder("spec-detached")
                    .category(SpanCategory.INTERNAL)
                    .reason(SpanSpecReason.PLATFORM_EDGE_CASE)
                    .detached()
                    .build();
            tracing.manual().spanFromSpec(spec).start().close();

            SpanData span = findSpan(exporter, "spec-detached");
            assertThat(span.getParentSpanId()).isIn("", INVALID_SPAN_ID);
            assertThat(span.getLinks()).isEmpty();
        });
    }

    @Test
    void spanFromSpec_detachedWithLinks_failsFastAtBuild() {
        SpanLinkContext link = SpanLinkContext.sampled(
                "0102030405060708090a0b0c0d0e0f10", "0102030405060708");
        assertThatThrownBy(() -> SpanSpec.builder("bad-spec")
                .category(SpanCategory.INTERNAL)
                .reason(SpanSpecReason.PLATFORM_EDGE_CASE)
                .detached()
                .linkedTo(link)
                .build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DETACHED");
    }

    private static SpanData findSpan(InMemorySpanExporter exporter, String name) {
        List<SpanData> spans = exporter.getFinishedSpanItems();
        return spans.stream()
                .filter(span -> name.equals(span.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Span not found: " + name
                        + "; exported=" + spans.stream().map(SpanData::getName).toList()));
    }

    @Configuration
    static class MeteredTopologyTestConfiguration {

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
