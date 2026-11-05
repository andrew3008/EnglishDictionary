package space.br1440.platform.tracing.otel.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import space.br1440.platform.tracing.otel.semconv.SemconvKeys;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SdkTracerProviderBuilder;
import io.opentelemetry.sdk.trace.SpanLimits;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.sdk.trace.samplers.SamplingDecision;
import io.opentelemetry.sdk.trace.samplers.SamplingResult;

class ExceptionRecorderSafetyTest {

    @ParameterizedTest
    @CsvSource({
            "wrapped, false", "dropped, false", "ended, false", "invalid, false",
            "wrapped, true", "dropped, true", "ended, true", "invalid, true"
    })
    void nonRecordingSpansDoNotReadException(String state, boolean eventOnly) {
        // given
        ExceptionRecorder recorder = new ExceptionRecorder(new ExceptionRecordingPolicy(true, true, 1_000));
        CountingException exception = new CountingException("private-message", null);
        Sampler sampler = state.equals("dropped") ? Sampler.alwaysOff() : Sampler.alwaysOn();
        try (SdkTracerProvider provider = recordingProvider().setSampler(sampler).build()) {
            Span span = switch (state) {
                case "wrapped" -> Span.wrap(SpanContext.create(
                        "11111111111111111111111111111111", "1111111111111111",
                        TraceFlags.getDefault(), TraceState.getDefault()));
                case "invalid" -> Span.getInvalid();
                default -> provider.get("test").spanBuilder(state).startSpan();
            };
            if (state.equals("ended")) {
                span.end();
            }

            // when
            if (eventOnly) {
                recorder.recordEvent(span, exception);
            } else {
                recorder.record(span, exception);
            }

            // then
            assertThat(span.isRecording()).isFalse();
            assertThat(exception.messageReads).isZero();
            assertThat(exception.causeReads).isZero();
            assertThat(exception.stackReads).isZero();
        }
    }

    @Test
    void recordingUnsampledSpanStillCapturesException() {
        // given
        Sampler sampler = new Sampler() {
            @Override
            public SamplingResult shouldSample(Context parentContext, String traceId, String name,
                                               SpanKind spanKind, Attributes attributes, List<LinkData> parentLinks) {
                return SamplingResult.create(SamplingDecision.RECORD_ONLY);
            }

            @Override
            public String getDescription() {
                return "RecordOnly";
            }
        };
        try (SdkTracerProvider provider = recordingProvider().setSampler(sampler).build()) {
            Span span = provider.get("test").spanBuilder("record-only").startSpan();

            // when
            ExceptionRecorder.secureDefault().record(span, new IllegalStateException("private-message"));
            span.end();

            // then
            SpanData data = ((ReadableSpan) span).toSpanData();
            assertThat(data.getSpanContext().isSampled()).isFalse();
            assertThat(data.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
            assertThat(data.getEvents()).hasSize(1);
        }
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void messagePolicyAppliesToStatusEventAndCauseHeaders(boolean includeMessage, boolean includeStack) {
        // given
        CountingException cause = new CountingException("password=cause-secret", null);
        CountingException exception = new CountingException("token=outer-secret", cause);
        ExceptionRecorder recorder = new ExceptionRecorder(new ExceptionRecordingPolicy(includeMessage, includeStack, 1_000));
        try (SdkTracerProvider provider = recordingProvider().build()) {
            Span span = provider.get("test").spanBuilder("privacy").startSpan();

            // when
            recorder.record(span, exception);
            span.end();

            // then
            SpanData data = ((ReadableSpan) span).toSpanData();
            EventData event = data.getEvents().getFirst();
            String message = event.getAttributes().get(SemconvKeys.EXCEPTION_MESSAGE);
            String stack = event.getAttributes().get(SemconvKeys.EXCEPTION_STACKTRACE);
            assertThat(data.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
            assertThat(data.getStatus().getDescription()).isEqualTo(includeMessage ? "<redacted>" : "");
            assertThat(message).isEqualTo(includeMessage ? "<redacted>" : null);
            assertThat(exception.messageReads).isEqualTo(includeMessage ? 1 : 0);
            assertThat(cause.messageReads).isEqualTo(includeMessage && includeStack ? 1 : 0);
            if (includeStack) {
                assertThat(stack).contains("Caused by:", CountingException.class.getName());
                assertThat(exception.stackReads).isEqualTo(1);
                assertThat(cause.stackReads).isEqualTo(1);
                if (includeMessage) {
                    assertThat(stack).contains(": <redacted>\n", "Caused by: " + CountingException.class.getName() + ": <redacted>\n");
                } else {
                    assertThat(stack).doesNotContain("<redacted>");
                }
                assertThat(stack).doesNotContain("outer-secret", "cause-secret");
            } else {
                assertThat(stack).isNull();
                assertThat(exception.causeReads).isZero();
                assertThat(exception.stackReads).isZero();
            }
        }
    }

    @Test
    void rootMessageIsReadOnceAndReusedOnAllSurfaces() {
        // given
        CountingException exception = new CountingException("message", null) {
            @Override
            public String getMessage() {
                return super.getMessage() + "-" + messageReads;
            }
        };
        ExceptionRecorder recorder = new ExceptionRecorder(new ExceptionRecordingPolicy(true, true, 1_000));
        try (SdkTracerProvider provider = recordingProvider().build()) {
            Span span = provider.get("test").spanBuilder("once").startSpan();

            // when
            recorder.record(span, exception);
            span.end();

            // then
            SpanData data = ((ReadableSpan) span).toSpanData();
            EventData event = data.getEvents().getFirst();
            assertThat(exception.messageReads).isEqualTo(1);
            assertThat(data.getStatus().getDescription()).isEqualTo("message-1");
            assertThat(event.getAttributes().get(SemconvKeys.EXCEPTION_MESSAGE)).isEqualTo("message-1");
            assertThat(event.getAttributes().get(SemconvKeys.EXCEPTION_STACKTRACE)).contains(": message-1\n");
        }
    }

    @Test
    void fatalExtractionErrorIsNotSwallowed() {
        // given
        LinkageError fatal = new LinkageError("fatal sentinel");
        Throwable exception = new IllegalStateException() {
            @Override
            public String getMessage() {
                throw fatal;
            }
        };
        ExceptionRecorder recorder = new ExceptionRecorder(new ExceptionRecordingPolicy(true, true, 1_000));
        try (SdkTracerProvider provider = recordingProvider().build()) {
            Span span = provider.get("test").spanBuilder("fatal").startSpan();
            try {
                // when
                assertThatThrownBy(() -> recorder.record(span, exception)).isSameAs(fatal);

                // then
                SpanData data = ((ReadableSpan) span).toSpanData();
                assertThat(data.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
                assertThat(data.getAttributes().get(SemconvKeys.PLATFORM_RESULT)).isEqualTo("failure");
            } finally {
                span.end();
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void eventOnlyPathAppliesMessagePolicyWithoutStatusWrites(boolean includeMessage, boolean includeStack) {
        // given
        CountingException cause = new CountingException("password=cause-secret", null);
        CountingException exception = new CountingException("token=outer-secret", cause);
        ExceptionRecorder recorder = new ExceptionRecorder(new ExceptionRecordingPolicy(includeMessage, includeStack, 1_000));
        try (SdkTracerProvider provider = recordingProvider().build()) {
            Span span = provider.get("test").spanBuilder("privacy-event-only").startSpan();

            // when
            recorder.recordEvent(span, exception);
            span.end();

            // then
            SpanData data = ((ReadableSpan) span).toSpanData();
            EventData event = data.getEvents().getFirst();
            String stack = event.getAttributes().get(SemconvKeys.EXCEPTION_STACKTRACE);
            assertThat(data.getStatus().getStatusCode()).isEqualTo(StatusCode.UNSET);
            assertThat(data.getStatus().getDescription()).isEmpty();
            assertThat(data.getAttributes().get(SemconvKeys.ERROR_TYPE)).isNull();
            assertThat(data.getAttributes().get(SemconvKeys.PLATFORM_RESULT)).isNull();
            assertThat(event.getAttributes().get(SemconvKeys.EXCEPTION_MESSAGE)).isEqualTo(includeMessage ? "<redacted>" : null);
            assertThat(exception.messageReads).isEqualTo(includeMessage ? 1 : 0);
            assertThat(cause.messageReads).isEqualTo(includeMessage && includeStack ? 1 : 0);
            if (includeStack) {
                assertThat(stack).contains("Caused by:").doesNotContain("outer-secret", "cause-secret");
            } else {
                assertThat(stack).isNull();
                assertThat(exception.causeReads).isZero();
            }
        }
    }

    @Test
    void eventOnlyFatalExtractionErrorIsNotSwallowed() {
        // given
        LinkageError fatal = new LinkageError("fatal sentinel");
        Throwable exception = new IllegalStateException() {
            @Override
            public String getMessage() {
                throw fatal;
            }
        };
        ExceptionRecorder recorder = new ExceptionRecorder(new ExceptionRecordingPolicy(true, true, 1_000));
        try (SdkTracerProvider provider = recordingProvider().build()) {
            Span span = provider.get("test").spanBuilder("fatal-event-only").startSpan();
            try {
                // when
                assertThatThrownBy(() -> recorder.recordEvent(span, exception)).isSameAs(fatal);

                // then
                SpanData data = ((ReadableSpan) span).toSpanData();
                assertThat(data.getStatus().getStatusCode()).isEqualTo(StatusCode.UNSET);
                assertThat(data.getAttributes().get(SemconvKeys.PLATFORM_RESULT)).isNull();
                assertThat(data.getEvents()).isEmpty();
            } finally {
                span.end();
            }
        }
    }

    @Test
    void lowerSdkLimitCanTruncateFacadeStackAgain() {
        // given
        IllegalStateException cause = new IllegalStateException("root-marker");
        cause.setStackTrace(new StackTraceElement[0]);
        IllegalStateException exception = new IllegalStateException("outer-message-".repeat(10), cause);
        exception.setStackTrace(new StackTraceElement[0]);
        ExceptionRecorder recorder = new ExceptionRecorder(new ExceptionRecordingPolicy(true, true, 1_000));
        try (SdkTracerProvider provider = recordingProvider().build()) {
            Span span = provider.get("test").spanBuilder("default-limit").startSpan();
            recorder.record(span, exception);
            span.end();
            assertThat(((ReadableSpan) span).toSpanData().getEvents().getFirst().getAttributes()
                    .get(SemconvKeys.EXCEPTION_STACKTRACE)).contains("root-marker").hasSizeLessThanOrEqualTo(1000);
        }
        try (SdkTracerProvider provider = recordingProvider()
                .setSpanLimits(SpanLimits.builder().setMaxAttributeValueLength(80).build())
                .build()) {
            Span span = provider.get("test").spanBuilder("sdk-limit").startSpan();

            // when
            recorder.record(span, exception);
            span.end();

            // then
            String stack = ((ReadableSpan) span).toSpanData().getEvents().getFirst().getAttributes()
                    .get(SemconvKeys.EXCEPTION_STACKTRACE);
            assertThat(stack).hasSize(80).doesNotContain("root-marker");
        }
    }

    private static SdkTracerProviderBuilder recordingProvider() {
        return SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(InMemorySpanExporter.create()));
    }

    private static class CountingException extends RuntimeException {

        int messageReads;
        int causeReads;
        int stackReads;

        CountingException(String message, Throwable cause) {
            super(message, cause, false, false);
        }

        @Override
        public String getMessage() {
            messageReads++;
            return super.getMessage();
        }

        @Override
        public synchronized Throwable getCause() {
            causeReads++;
            return super.getCause();
        }

        @Override
        public StackTraceElement[] getStackTrace() {
            stackReads++;
            return super.getStackTrace();
        }
    }
}
