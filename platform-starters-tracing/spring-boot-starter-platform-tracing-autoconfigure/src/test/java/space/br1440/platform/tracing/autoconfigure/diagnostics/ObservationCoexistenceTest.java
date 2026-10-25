package space.br1440.platform.tracing.autoconfigure.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.observation.ObservationAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.opentelemetry.OpenTelemetryAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.tracing.MicrometerTracingAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.tracing.OpenTelemetryTracingAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.autoconfigure.metrics.MeteredTracingRuntime;
import space.br1440.platform.tracing.autoconfigure.metrics.PlatformTracingMetrics;
import space.br1440.platform.tracing.otel.attributes.validation.SpanAttributeValidator;
import space.br1440.platform.tracing.otel.exception.ExceptionRecorder;
import space.br1440.platform.tracing.otel.facade.DefaultTraceOperations;
import space.br1440.platform.tracing.otel.facade.NoopTraceOperations;
import space.br1440.platform.tracing.otel.runtime.NoOpTracingRuntime;
import space.br1440.platform.tracing.otel.runtime.TracingRuntime;
import space.br1440.platform.tracing.otel.runtime.otel.OtelTracingRuntime;

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

class ObservationCoexistenceTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    OpenTelemetryAutoConfiguration.class,
                    ObservationAutoConfiguration.class,
                    OpenTelemetryTracingAutoConfiguration.class,
                    MicrometerTracingAutoConfiguration.class)
            )
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
            try (Observation.Scope ignored = observation.openScope()) {
                tracing.spans().operation("business-logic").start().close();
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
            try (Observation.Scope ignored = observation.openScope()) {
                tracing.spans().operation("intentional-root").root().start().close();
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
    void disabledPlatformspanFactory_doesNotDisableSpringObservation() {
        contextRunner
                .withPropertyValues("platform.tracing.enabled=false")
                .run(context -> {
                    ObservationRegistry observationRegistry = context.getBean(ObservationRegistry.class);
                    TraceOperations tracing = context.getBean(TraceOperations.class);
                    InMemorySpanExporter exporter = context.getBean(InMemorySpanExporter.class);

                    assertThat(tracing).isInstanceOf(NoopTraceOperations.class);

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

            tracing.spans().operation("metered-delegate").start().close();

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
        return findSpanByNameIfPresent(spans, name)
                .orElseGet(() -> fail(
                                "Expected an exported span named '%s' but found none. Exported spans: %s",
                                name,
                                exportedNames(spans)
                        )
                );
    }

    private static Optional<SpanData> findSpanByNameIfPresent(List<SpanData> spans, String name) {
        return spans.stream()
                .filter(span -> span.getName().equals(name))
                .findFirst();
    }

    private static String exportedNames(List<SpanData> spans) {
        return spans.stream()
                .map(SpanData::getName)
                .collect(Collectors.joining(", ", "[", "]"));
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

        @Bean
        TracingRuntime tracingRuntime(OpenTelemetry openTelemetry,
                                      MeterRegistry meterRegistry,
                                      Environment environment) {
            if (!environment.getProperty("platform.tracing.enabled", Boolean.class, true)) {
                return NoOpTracingRuntime.disabledByConfiguration("test-disabled");
            }

            return new MeteredTracingRuntime(
                    new OtelTracingRuntime(
                            openTelemetry,
                            new SpanAttributeValidator(),
                            ExceptionRecorder.secureDefault()
                    ),
                    new PlatformTracingMetrics(meterRegistry)
            );
        }

        @Bean
        TraceOperations traceOperations(TracingRuntime tracingRuntime) {
            if (tracingRuntime instanceof NoOpTracingRuntime noOpTracingRuntime) {
                return NoopTraceOperations.forDisabledRuntime(noOpTracingRuntime);
            }

            return new DefaultTraceOperations(tracingRuntime);
        }
    }
}
