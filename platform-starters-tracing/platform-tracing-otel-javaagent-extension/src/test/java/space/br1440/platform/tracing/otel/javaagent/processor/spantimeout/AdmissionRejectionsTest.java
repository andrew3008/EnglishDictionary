package space.br1440.platform.tracing.otel.javaagent.processor.spantimeout;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.parallel.ResourceLock;

import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentEventCode;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLogCapture;
import space.br1440.platform.tracing.otel.javaagent.logging.CapturedLogEvent;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogLevel;
import space.br1440.platform.tracing.otel.javaagent.processor.spantimeout.AdmissionRejections.RejectionReason;

@ResourceLock(AgentLogCapture.RESOURCE_LOCK)
class AdmissionRejectionsTest {

    private static final long WINDOW_NANOS = Duration.ofSeconds(60).toNanos();

    private final AtomicLong clock = new AtomicLong();
    private final AdmissionRejections rejections = new AdmissionRejections(7, 500, 40, clock::get);

    @RegisterExtension
    final AgentLogCapture logs = new AgentLogCapture();

    @Test
    void countIncrementsOnlyTheCounterOfItsOwnReason() {
        // when
        rejections.count(RejectionReason.PER_TRACE_CAPACITY);
        rejections.count(RejectionReason.GLOBAL_CAPACITY);
        rejections.count(RejectionReason.GLOBAL_CAPACITY);
        rejections.count(RejectionReason.TRACE_CAPACITY);
        rejections.count(RejectionReason.TRACE_CAPACITY);
        rejections.count(RejectionReason.TRACE_CAPACITY);

        // then
        assertThat(rejections.perTraceTotal()).isEqualTo(1);
        assertThat(rejections.globalTotal()).isEqualTo(2);
        assertThat(rejections.traceCapacityTotal()).isEqualTo(3);
        assertThat(logs.events()).isEmpty();
    }

    @Test
    void logIfAnyIsSilentForNull() {
        // when
        rejections.logIfAny(null);

        // then
        assertThat(logs.events()).isEmpty();
    }

    @Test
    void logIfAnyEmitsOneWarnPerReasonPerWindowWithCounterAndLimit() {
        // given
        rejections.count(RejectionReason.GLOBAL_CAPACITY);
        rejections.count(RejectionReason.GLOBAL_CAPACITY);

        // when
        rejections.logIfAny(RejectionReason.GLOBAL_CAPACITY);
        clock.set(WINDOW_NANOS - 1L);
        rejections.logIfAny(RejectionReason.GLOBAL_CAPACITY);

        // then
        assertThat(logs.events()).singleElement().satisfies(event -> {
            assertThat(event.level()).isEqualTo(AgentLogLevel.WARN);
            assertThat(event.eventCode()).isEqualTo(AgentEventCode.EXPORT_SPAN_TIMEOUT);
            assertThat(event.message())
                    .isEqualTo("Span timeout rejected span tracking: global tracked-span capacity reached");
            assertThat(event.fields()).containsEntry("rejected", "2").containsEntry("limit", "500");
        });
    }

    @Test
    void rateLimitWindowsAreIndependentPerReason() {
        // given
        rejections.logIfAny(RejectionReason.GLOBAL_CAPACITY);

        // when
        rejections.logIfAny(RejectionReason.PER_TRACE_CAPACITY);
        rejections.logIfAny(RejectionReason.TRACE_CAPACITY);
        rejections.logIfAny(RejectionReason.GLOBAL_CAPACITY);

        // then
        assertThat(logs.events()).extracting(CapturedLogEvent::message).containsExactly(
                "Span timeout rejected span tracking: global tracked-span capacity reached",
                "Span timeout rejected span tracking: per-trace tracked-span capacity reached",
                "Span timeout rejected span tracking: tracked-trace capacity reached");
        assertThat(logs.events()).extracting(event -> event.fields().get("limit"))
                .containsExactly("500", "7", "40");
    }

    @Test
    void logResumesAfterTheWindowElapses() {
        // given
        rejections.logIfAny(RejectionReason.TRACE_CAPACITY);

        // when
        clock.set(WINDOW_NANOS);
        rejections.logIfAny(RejectionReason.TRACE_CAPACITY);

        // then
        assertThat(logs.events()).hasSize(2);
    }
}
