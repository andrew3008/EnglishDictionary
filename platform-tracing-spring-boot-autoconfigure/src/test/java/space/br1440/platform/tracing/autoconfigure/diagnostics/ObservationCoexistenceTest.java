package space.br1440.platform.tracing.autoconfigure.diagnostics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.observation.ObservationAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.opentelemetry.OpenTelemetryAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.tracing.MicrometerTracingAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.tracing.OpenTelemetryTracingAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.autoconfigure.TracingCoreAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.TracingMetricsAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.metrics.MeteredTracingRuntime;
import space.br1440.platform.tracing.core.facade.DefaultTraceOperations;
import space.br1440.platform.tracing.core.facade.NoopTraceOperations;
import space.br1440.platform.tracing.core.runtime.TracingRuntime;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Slice 7 hard gate: Micrometer Observation and platform manual tracing coexist without
 * duplicate unsynchronized roots.
 */
class ObservationCoexistenceTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    OpenTelemetryAutoConfiguration.class,
                    ObservationAutoConfiguration.class,
                    OpenTelemetryTracingAutoConfiguration.class,
                    MicrometerTracingAutoConfiguration.class,
                    TracingCoreAutoConfiguration.class,
                    TracingMetricsAutoConfiguration.class))
            .withUserConfiguration(ObservationCoexistenceTestConfiguration.class)
            .withPropertyValues("spring.application.name=observation-coexistence");

    @Test
    void manualOperationInsideObservation_isChildOfObservedRoot_notCompetingRoot() {
        contextRunner.run(context -> {
            ObservationRegistry observationRegistry = context.getBean(ObservationRegistry.class);
            TraceOperations tracing = context.getBean(TraceOperations.class);
            InMemorySpanExporter exporter = context.getBean(InMemorySpanExporter.class);

            Observation observation = Observation.createNotStarted("app.request", observationRegistry)
                    .start();
            try (Observation.Scope scope = observation.openScope()) {
                tracing.manual().operation("business-logic").start().close();
            } finally {
                observation.stop();
            }

            List<SpanData> spans = exporter.getFinishedSpanItems();
            assertThat(spans).hasSize(2);
            assertThat(distinctTraceIds(spans)).hasSize(1);
            assertThat(rootSpanCount(spans)).isEqualTo(1);

            SpanData manual = findSpan(spans, "business-logic");
            assertThat(manual.getParentSpanContext().isValid()).isTrue();
            assertThat(manual.getTraceId()).isEqualTo(findSpan(spans, "app.request").getTraceId());
        });
    }

    @Test
    void intentionalManualRoot_insideObservation_createsSeparateTrace() {
        contextRunner.run(context -> {
            ObservationRegistry observationRegistry = context.getBean(ObservationRegistry.class);
            TraceOperations tracing = context.getBean(TraceOperations.class);
            InMemorySpanExporter exporter = context.getBean(InMemorySpanExporter.class);

            Observation observation = Observation.createNotStarted("app.request", observationRegistry)
                    .start();
            try (Observation.Scope scope = observation.openScope()) {
                tracing.manual().operation("intentional-root").root().start().close();
            } finally {
                observation.stop();
            }

            List<SpanData> spans = exporter.getFinishedSpanItems();
            assertThat(spans).hasSize(2);
            assertThat(rootSpanCount(spans)).isEqualTo(2);
            assertThat(distinctTraceIds(spans)).hasSize(2);
        });
    }

    @Test
    void disabledPlatformManualTracing_doesNotDisableSpringObservation() {
        contextRunner
                .withPropertyValues("platform.tracing.sdk.mode=DISABLED")
                .run(context -> {
                    ObservationRegistry observationRegistry = context.getBean(ObservationRegistry.class);
                    TraceOperations tracing = context.getBean(TraceOperations.class);
                    InMemorySpanExporter exporter = context.getBean(InMemorySpanExporter.class);

                    assertThat(tracing).isInstanceOf(NoopTraceOperations.class);
                    assertThat(context.getBean(ManualTracingDiagnostics.class).view().mode())
                            .isEqualTo("DISABLED_BY_CONFIGURATION");

                    Observation observation = Observation.createNotStarted("observation-only", observationRegistry)
                            .start();
                    observation.stop();

                    assertThat(exporter.getFinishedSpanItems()).hasSize(1);
                });
    }

    @Test
    void meteredImplementation_doesNotCreateSpansDirectly() {
        contextRunner.run(context -> {
            TracingRuntime tracingImplementation = context.getBean(TracingRuntime.class);
            TraceOperations tracing = context.getBean(TraceOperations.class);
            InMemorySpanExporter exporter = context.getBean(InMemorySpanExporter.class);

            assertThat(tracingImplementation).isInstanceOf(MeteredTracingRuntime.class);
            assertThat(tracing).isInstanceOf(DefaultTraceOperations.class);

            tracing.manual().operation("metered-delegate").start().close();

            assertThat(exporter.getFinishedSpanItems())
                    .extracting(SpanData::getName)
                    .containsExactly("metered-delegate");
        });
    }

    private static Set<String> distinctTraceIds(List<SpanData> spans) {
        return spans.stream().map(SpanData::getTraceId).collect(Collectors.toSet());
    }

    private static long rootSpanCount(List<SpanData> spans) {
        return spans.stream().filter(ObservationCoexistenceTest::isRoot).count();
    }

    private static boolean isRoot(SpanData span) {
        return !span.getParentSpanContext().isValid()
                || span.getParentSpanId().isEmpty()
                || "0000000000000000".equals(span.getParentSpanId());
    }

    private static SpanData findSpan(List<SpanData> spans, String name) {
        return spans.stream()
                .filter(span -> name.equals(span.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Span not found: " + name
                        + "; exported=" + spans.stream().map(SpanData::getName).toList()));
    }

    @Configuration
    static class ObservationCoexistenceTestConfiguration {

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

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }
}
