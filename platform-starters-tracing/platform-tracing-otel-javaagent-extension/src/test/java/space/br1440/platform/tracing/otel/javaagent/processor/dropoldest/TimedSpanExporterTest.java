package space.br1440.platform.tracing.otel.javaagent.processor.dropoldest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.parallel.ResourceLock;

import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSourceTestFixtures;
import space.br1440.platform.tracing.otel.javaagent.exporter.SafeSpanExporter;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLogCapture;
import space.br1440.platform.tracing.otel.javaagent.logging.CapturedLogEvent;
import space.br1440.platform.tracing.otel.javaagent.logging.RateLimitedLogger;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentEventCode;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime.ScrubbingRuntimeTestFixtures;

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;

@ResourceLock(AgentLogCapture.RESOURCE_LOCK)
class TimedSpanExporterTest {

    private static final long TIMEOUT_MILLIS = 23L;
    private static final long TIMEOUT_NANOS = TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MILLIS);

    @RegisterExtension
    final AgentLogCapture capture = new AgentLogCapture();

    @Test
    void exportFailuresUseCatalogEventsAndTypedTimeout() {
        // given
        DropOldestExportMetrics metrics = new DropOldestExportMetrics();
        RuntimeException thrown = new RuntimeException("sensitive-export-detail");
        TimedSpanExporter throwingExporter = exporter(() -> {
            throw thrown;
        }, CompletableResultCode::ofSuccess, metrics);
        TimedSpanExporter nullExporter = exporter(() -> null, CompletableResultCode::ofSuccess, metrics);
        TimedSpanExporter timedOutExporter =
                exporter(CompletableResultCode::new, CompletableResultCode::ofSuccess, metrics);
        TimedSpanExporter failedExporter =
                exporter(CompletableResultCode::ofFailure, CompletableResultCode::ofSuccess, metrics);

        // when
        List<TimedExport> results = List.of(
                throwingExporter.export(List.of()),
                nullExporter.export(List.of()),
                timedOutExporter.export(List.of()),
                failedExporter.export(List.of())
        );

        // then
        assertThat(results).extracting(TimedExport::success).containsExactly(false, false, false, false);
        assertThat(results.get(0).unfinished()).isNull();
        assertThat(results.get(1).unfinished()).isNull();
        assertThat(results.get(2).unfinished()).isNotNull();
        assertThat(results.get(2).unfinished().isDone()).isFalse();
        assertThat(results.get(3).unfinished()).isNull();
        assertThat(metrics.failuresTotal()).isEqualTo(4L);
        assertThat(metrics.timeoutsTotal()).isEqualTo(1L);

        List<CapturedLogEvent> events = capture.events();
        assertThat(events).extracting(CapturedLogEvent::eventCode)
                .containsExactly(
                        AgentEventCode.EXPORT_TIMED,
                        AgentEventCode.EXPORT_TIMED,
                        AgentEventCode.EXPORT_TIMED,
                        AgentEventCode.EXPORT_TIMED
                );
        assertThat(events).extracting(CapturedLogEvent::message)
                .containsExactly(
                        "exporter.export() threw",
                        "exporter.export() returned null",
                        "exporter.export() timed out",
                        "exporter.export() returned failure"
                );
        assertThat(events.get(0).throwableDigest())
                .contains(RuntimeException.class.getName())
                .doesNotContain("sensitive-export-detail");
        assertThat(events.get(1).throwableDigest()).isNull();
        assertThat(events.get(2).throwableDigest()).isNull();
        assertThat(events.get(3).throwableDigest()).isNull();
        assertThat(events.get(0).fields()).isEmpty();
        assertThat(events.get(1).fields()).isEmpty();
        assertThat(events.get(2).fields()).containsExactlyEntriesOf(Map.of(
                "timeoutMillis", Long.toString(TIMEOUT_MILLIS)
        ));
        assertThat(events.get(3).fields()).isEmpty();
    }

    @Test
    void shutdownFailuresUseCatalogEventsAndTypedTimeout() {
        // given
        DropOldestExportMetrics metrics = new DropOldestExportMetrics();
        IllegalStateException thrown = new IllegalStateException("sensitive-shutdown-detail");
        TimedSpanExporter throwingExporter = exporter(CompletableResultCode::ofSuccess, () -> {
            throw thrown;
        }, metrics);
        TimedSpanExporter nullExporter = exporter(CompletableResultCode::ofSuccess, () -> null, metrics);
        TimedSpanExporter timedOutExporter =
                exporter(CompletableResultCode::ofSuccess, CompletableResultCode::new, metrics);
        TimedSpanExporter failedExporter =
                exporter(CompletableResultCode::ofSuccess, CompletableResultCode::ofFailure, metrics);

        // when
        List<Boolean> results = List.of(
                throwingExporter.shutdown(),
                nullExporter.shutdown(),
                timedOutExporter.shutdown(),
                failedExporter.shutdown()
        );

        // then
        assertThat(results).containsExactly(false, false, false, false);
        assertThat(metrics.shutdownFailuresTotal()).isEqualTo(4L);

        List<CapturedLogEvent> events = capture.events();
        assertThat(events).extracting(CapturedLogEvent::eventCode)
                .containsExactly(
                        AgentEventCode.EXPORT_TIMED,
                        AgentEventCode.EXPORT_TIMED,
                        AgentEventCode.EXPORT_TIMED,
                        AgentEventCode.EXPORT_TIMED
                );
        assertThat(events).extracting(CapturedLogEvent::message)
                .containsExactly(
                        "exporter.shutdown() failed",
                        "exporter.shutdown() returned null",
                        "exporter.shutdown() timed out",
                        "exporter.shutdown() returned failure"
                );
        assertThat(events.get(0).throwableDigest())
                .contains(IllegalStateException.class.getName())
                .doesNotContain("sensitive-shutdown-detail");
        assertThat(events.get(1).throwableDigest()).isNull();
        assertThat(events.get(2).throwableDigest()).isNull();
        assertThat(events.get(3).throwableDigest()).isNull();
        assertThat(events.get(0).fields()).isEmpty();
        assertThat(events.get(1).fields()).isEmpty();
        assertThat(events.get(2).fields()).containsExactlyEntriesOf(Map.of(
                "timeoutMillis", Long.toString(TIMEOUT_MILLIS)
        ));
        assertThat(events.get(3).fields()).isEmpty();
    }

    @Test
    void processorTimeoutWithoutDelegateCompletionDoesNotOpenRecoveryEpisode() {
        // given
        CompletableResultCode pending = new CompletableResultCode();
        AtomicInteger calls = new AtomicInteger();
        SpanExporter transport = new SpanExporter() {
            @Override
            public CompletableResultCode export(Collection<SpanData> spans) {
                return calls.getAndIncrement() == 0 ? pending : CompletableResultCode.ofSuccess();
            }

            @Override
            public CompletableResultCode flush() {
                return CompletableResultCode.ofSuccess();
            }

            @Override
            public CompletableResultCode shutdown() {
                return CompletableResultCode.ofSuccess();
            }
        };

        SafeSpanExporter safe = new SafeSpanExporter(
                transport,
                TracingSettingsSourceTestFixtures.boundDefaults(AgentInstanceId.random()),
                ScrubbingRuntimeTestFixtures.disabled()
        );
        DropOldestExportMetrics metrics = new DropOldestExportMetrics();
        TimedSpanExporter timed = new TimedSpanExporter(safe, TIMEOUT_NANOS, TIMEOUT_NANOS, metrics);

        // when
        TimedExport outcome = timed.export(List.of());
        safe.export(List.of());

        // then
        assertThat(outcome.success()).isFalse();
        assertThat(outcome.unfinished()).isSameAs(pending);
        assertThat(pending.isDone()).isFalse();
        assertThat(metrics.timeoutsTotal()).isEqualTo(1L);
        assertThat(safe.getExportFailures()).isZero();
        assertThat(capture.events())
                .noneMatch(event -> "SAFE_EXPORT_SUCCEEDED_AFTER_FAILURES".equals(event.eventName()));
    }

    private static TimedSpanExporter exporter(Supplier<CompletableResultCode> exportResult,
                                              Supplier<CompletableResultCode> shutdownResult,
                                              DropOldestExportMetrics metrics) {
        SpanExporter delegate = new SpanExporter() {
            @Override
            public CompletableResultCode export(Collection<SpanData> spans) {
                return exportResult.get();
            }

            @Override
            public CompletableResultCode flush() {
                return CompletableResultCode.ofSuccess();
            }

            @Override
            public CompletableResultCode shutdown() {
                return shutdownResult.get();
            }
        };

        return new TimedSpanExporter(
                delegate,
                TIMEOUT_NANOS,
                TIMEOUT_NANOS,
                metrics,
                new RateLimitedLogger(TimedSpanExporter.class)
        );
    }
}
