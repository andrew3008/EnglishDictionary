package space.br1440.platform.tracing.autoconfigure.exception;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import space.br1440.platform.tracing.otel.exception.ExceptionRecorder;
import space.br1440.platform.tracing.otel.exception.ExceptionRecordingPolicy;
import space.br1440.platform.tracing.otel.semconv.SemconvKeys;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

import static org.assertj.core.api.Assertions.assertThat;

class ExceptionRecordingAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ExceptionRecordingAutoConfiguration.class));

    @Test
    void defaultRecorderIsUniqueAndDoesNotExposePolicyBean() {
        contextRunner.run(context -> {
            // given / when / then
            assertThat(context).hasSingleBean(ExceptionRecorder.class);
            assertThat(context).hasBean("platformExceptionRecorder");
            assertThat(context).doesNotHaveBean(ExceptionRecordingPolicy.class);
        });
    }

    @Test
    void includeMessageAndStacktracePropertiesControlRecordedEvent() {
        contextRunner
                .withPropertyValues(
                        "platform.tracing.exception-recording.include-message=true",
                        "platform.tracing.exception-recording.include-stacktrace=true")
                .run(context -> {
                    // given
                    ExceptionRecorder recorder = context.getBean(ExceptionRecorder.class);

                    InMemorySpanExporter exporter = InMemorySpanExporter.create();
                    try (SdkTracerProvider provider = SdkTracerProvider.builder()
                            .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                            .build()
                    ) {
                        Tracer tracer = provider.get("test");
                        Span span = tracer.spanBuilder("op").startSpan();
                        recorder.record(span, new IllegalStateException("boom"));
                        span.end();

                        // when
                        var finished = exporter.getFinishedSpanItems();

                        // then
                        assertThat(finished).isNotEmpty();
                        var event = finished.getFirst().getEvents().getFirst();
                        assertThat(event.getAttributes().get(SemconvKeys.EXCEPTION_MESSAGE)).isEqualTo("boom");
                        assertThat(event.getAttributes().get(SemconvKeys.EXCEPTION_STACKTRACE))
                                .contains("IllegalStateException");
                    }
                });
    }
}
