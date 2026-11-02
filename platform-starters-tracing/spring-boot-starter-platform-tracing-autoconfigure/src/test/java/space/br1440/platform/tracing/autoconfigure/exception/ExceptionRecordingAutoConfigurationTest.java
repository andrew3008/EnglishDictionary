package space.br1440.platform.tracing.autoconfigure.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import space.br1440.platform.tracing.autoconfigure.TracingProperties;
import space.br1440.platform.tracing.otel.exception.ExceptionRecorder;
import space.br1440.platform.tracing.otel.exception.ExceptionRecordingPolicy;
import space.br1440.platform.tracing.otel.semconv.SemconvKeys;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

class ExceptionRecordingAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ExceptionRecordingAutoConfiguration.class));

    @Test
    void defaultRecorderIsUniqueAndDoesNotExposePolicyBean() {
        // when
        contextRunner.run(context -> {
            // then
            assertThat(context).hasSingleBean(ExceptionRecorder.class);
            assertThat(context).hasBean("platformExceptionRecorder");
            assertThat(context).doesNotHaveBean(ExceptionRecordingPolicy.class);
        });
    }

    @Test
    void defaultsDoNotReadOrPublishExceptionText() {
        // given
        CountingMessageException failure = new CountingMessageException("outer-secret", null);

        // when
        contextRunner.run(context -> {
            ExceptionRecorder recorder = context.getBean(ExceptionRecorder.class);
            InMemorySpanExporter exporter = InMemorySpanExporter.create();
            try (SdkTracerProvider provider = SdkTracerProvider.builder()
                    .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                    .build()) {
                Span span = provider.get("test").spanBuilder("op").startSpan();
                recorder.record(span, failure);
                span.end();

                // then
                var properties = context.getBean(TracingProperties.class).getExceptionRecording();
                assertThat(properties.isIncludeMessage()).isFalse();
                assertThat(properties.isIncludeStacktrace()).isFalse();
                var finished = exporter.getFinishedSpanItems().getFirst();
                assertThat(finished.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
                assertThat(finished.getStatus().getDescription()).isEmpty();
                var event = finished.getEvents().getFirst();
                assertThat(event.getAttributes().get(SemconvKeys.EXCEPTION_TYPE))
                        .isEqualTo(CountingMessageException.class.getName());
                assertThat(event.getAttributes().get(SemconvKeys.EXCEPTION_MESSAGE)).isNull();
                assertThat(event.getAttributes().get(SemconvKeys.EXCEPTION_STACKTRACE)).isNull();
                assertThat(failure.messageReads).isZero();
            }
        });
    }

    @Test
    void stacktraceOptInKeepsMessagesDisabledForEntireCauseChain() {
        // given
        CountingMessageException cause = new CountingMessageException("cause-secret", null);
        CountingMessageException failure = new CountingMessageException("outer-secret", cause);

        // when
        contextRunner
                .withPropertyValues("platform.tracing.exception-recording.include-stacktrace=true")
                .run(context -> {
                    ExceptionRecorder recorder = context.getBean(ExceptionRecorder.class);
                    InMemorySpanExporter exporter = InMemorySpanExporter.create();
                    try (SdkTracerProvider provider = SdkTracerProvider.builder()
                            .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                            .build()) {
                        Span span = provider.get("test").spanBuilder("op").startSpan();
                        recorder.record(span, failure);
                        span.end();

                        // then
                        var finished = exporter.getFinishedSpanItems().getFirst();
                        assertThat(finished.getStatus().getDescription()).isEmpty();
                        var event = finished.getEvents().getFirst();
                        assertThat(event.getAttributes().get(SemconvKeys.EXCEPTION_MESSAGE)).isNull();
                        assertThat(event.getAttributes().get(SemconvKeys.EXCEPTION_STACKTRACE))
                                .contains(CountingMessageException.class.getName(), "Caused by:")
                                .doesNotContain("outer-secret", "cause-secret");
                        assertThat(failure.messageReads).isZero();
                        assertThat(cause.messageReads).isZero();
                    }
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

                        // when
                        recorder.record(span, new IllegalStateException("boom"));
                        span.end();
                        var finished = exporter.getFinishedSpanItems();

                        // then
                        assertThat(finished).hasSize(1);
                        assertThat(finished.getFirst().getStatus().getDescription()).isEqualTo("boom");
                        var event = finished.getFirst().getEvents().getFirst();
                        assertThat(event.getAttributes().get(SemconvKeys.EXCEPTION_MESSAGE)).isEqualTo("boom");
                        assertThat(event.getAttributes().get(SemconvKeys.EXCEPTION_STACKTRACE))
                                .contains("IllegalStateException: boom");
                    }
                });
    }

    @Test
    void truncatedStackKeepsCauseWithinBudget() {
        // given
        IllegalStateException cause = new IllegalStateException("root-marker");
        cause.setStackTrace(new StackTraceElement[0]);
        IllegalStateException outer = new IllegalStateException("outer", cause);
        StackTraceElement[] frames = new StackTraceElement[40];
        for (int index = 0; index < frames.length; index++) {
            frames[index] = new StackTraceElement(
                    "com.example.VeryLongClassNameThatConsumesBudget" + index,
                    "method",
                    "Type.java",
                    index + 1
            );
        }
        outer.setStackTrace(frames);

        // when
        contextRunner
                .withPropertyValues(
                        "platform.tracing.exception-recording.include-message=true",
                        "platform.tracing.exception-recording.include-stacktrace=true")
                .run(context -> {
                    ExceptionRecorder recorder = context.getBean(ExceptionRecorder.class);
                    InMemorySpanExporter exporter = InMemorySpanExporter.create();
                    try (SdkTracerProvider provider = SdkTracerProvider.builder()
                            .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                            .build()) {
                        Tracer tracer = provider.get("test");
                        Span span = tracer.spanBuilder("op").startSpan();
                        recorder.record(span, outer);
                        span.end();
                        String stack = exporter.getFinishedSpanItems().getFirst().getEvents().getFirst()
                                .getAttributes().get(SemconvKeys.EXCEPTION_STACKTRACE);

                        // then
                        assertThat(stack).contains("root-marker");
                        assertThat(stack).contains("... (character budget exhausted)");
                        assertThat(stack).hasSizeLessThanOrEqualTo(1_000);
                    }
                });
    }

    private static final class CountingMessageException extends RuntimeException {
        private int messageReads;

        private CountingMessageException(String message, Throwable cause) {
            super(message, cause);
            setStackTrace(new StackTraceElement[0]);
        }

        @Override
        public String getMessage() {
            messageReads++;
            return super.getMessage();
        }
    }
}
