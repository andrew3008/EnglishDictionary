package space.br1440.platform.tracing.otel.javaagent.exporter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.AbstractCollection;
import java.util.AbstractList;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import jakarta.annotation.Nonnull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import space.br1440.platform.tracing.api.spi.SpanAttributeScrubbingRule;
import space.br1440.platform.tracing.otel.javaagent.control.AcceptedPolicyMutationAccess;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSourceTestFixtures;
import space.br1440.platform.tracing.otel.javaagent.exporter.response.ExportResponseObservation;
import space.br1440.platform.tracing.otel.javaagent.exporter.response.ExportResponseScope;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.JmxWireContract;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLogCapture;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLoggingRuntime;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLoggingTestSupport;
import space.br1440.platform.tracing.otel.javaagent.logging.CapturedLogEvent;
import space.br1440.platform.tracing.otel.javaagent.logging.RateLimitedLogger;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentEventCode;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogLevel;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.rules.BuiltInSpanAttributeScrubbingRules;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime.ScrubbingRuntime;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime.ScrubbingRuntimeTestFixtures;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.DelegatingSpanData;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;

@ResourceLock(AgentLogCapture.RESOURCE_LOCK)
class SafeSpanExporterTest {

    private static Collection<SpanData> spansOfSize(int n) {
        return Collections.nCopies(n, null);
    }

    private static SafeSpanExporter safeExporter(SpanExporter delegate) {
        return safeExporter(delegate, TracingSettingsSourceTestFixtures.boundDefaults(AgentInstanceId.random()),
                ScrubbingRuntimeTestFixtures.disabled());
    }

    private static SafeSpanExporter safeExporter(SpanExporter delegate, TracingSettingsSource acceptedPolicy) {
        return safeExporter(delegate, acceptedPolicy, ScrubbingRuntimeTestFixtures.disabled());
    }

    private static SafeSpanExporter safeExporter(SpanExporter delegate,
                                                 TracingSettingsSource acceptedPolicy,
                                                 ScrubbingRuntime runtime) {
        return new SafeSpanExporter(delegate, acceptedPolicy, runtime);
    }

    private static SafeSpanExporter safeExporter(SpanExporter delegate, LongSupplier nanoClock) {
        return new SafeSpanExporter(
                delegate,
                TracingSettingsSourceTestFixtures.boundDefaults(AgentInstanceId.random()),
                ScrubbingRuntimeTestFixtures.disabled(),
                new RateLimitedLogger(SafeSpanExporterTest.class),
                new RateLimitedLogger(SafeSpanExporterTest.class),
                nanoClock
        );
    }

    @Test
    @DisplayName("Успешный export: exported += size, failures и dropped не растут")
    void successfulExportCountsExportedSpans() {
        // given
        SafeSpanExporter safe = safeExporter(delegate(CompletableResultCode.ofSuccess()));

        // when
        CompletableResultCode result = safe.export(spansOfSize(3));

        // then
        assertThat(result.isSuccess()).isTrue();
        assertThat(safe.getExportedSpans()).isEqualTo(3);
        assertThat(safe.getExportFailures()).isZero();
        assertThat(safe.getDroppedSpans()).isZero();
        assertThat(safe.getExportBatches()).isEqualTo(1);
    }

    @Test
    @DisplayName("Failed-результат делегата: failures += 1 (batch), dropped += size (spans)")
    void failedResultIncrementsBatchFailureAndSpanDrops() {
        // given
        SafeSpanExporter safe = safeExporter(delegate(CompletableResultCode.ofFailure()));

        // when
        CompletableResultCode result = safe.export(spansOfSize(5));

        // then
        assertThat(result.isSuccess()).isFalse();
        assertThat(safe.getExportFailures()).as("один упавший batch").isEqualTo(1);
        assertThat(safe.getDroppedSpans()).as("пять потерянных span'ов").isEqualTo(5);
        assertThat(safe.getExportedSpans()).isZero();
    }

    @Test
    @DisplayName("Successful export logs one fixed recovery event after an emitted failure")
    void successfulExportLogsOneFixedRecoveryEventAfterFailure() {
        // given
        AtomicInteger calls = new AtomicInteger();
        SpanExporter sequencedDelegate = new SpanExporter() {
            @Override
            public CompletableResultCode export(Collection<SpanData> spans) {
                return calls.getAndIncrement() == 0
                        ? CompletableResultCode.ofFailure()
                        : CompletableResultCode.ofSuccess();
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
        RateLimitedLogger recoveryLog = new RateLimitedLogger(SafeSpanExporterTest.class);
        try (SafeSpanExporter safe = new SafeSpanExporter(
                sequencedDelegate,
                TracingSettingsSourceTestFixtures.boundDefaults(AgentInstanceId.random()),
                ScrubbingRuntimeTestFixtures.disabled(),
                recoveryLog,
                new RateLimitedLogger(SafeSpanExporterTest.class),
                System::nanoTime);
             AgentLogCapture capture = AgentLogCapture.installed()) {
            // when
            safe.export(spansOfSize(1));
            safe.export(spansOfSize(1));
            safe.export(spansOfSize(1));

            // then
            assertThat(capture.events())
                    .filteredOn(event -> "SAFE_EXPORT_SUCCEEDED_AFTER_FAILURES".equals(event.eventName()))
                    .singleElement()
                    .satisfies(event -> {
                        assertThat(event.eventCode()).isEqualTo(AgentEventCode.EXPORT_SAFE_EXPORTER);
                        assertThat(event.level()).isEqualTo(AgentLogLevel.INFO);
                        assertThat(event.message()).isEqualTo(
                                "Span export delegate succeeded after terminal export failures"
                        );
                        assertThat(event.fields()).containsEntry("failures", "1");
                    });
        }
    }

    @Test
    void exporterInstancesDoNotShareRecoveryState() {
        // given
        AtomicInteger firstCalls = new AtomicInteger();
        SafeSpanExporter first = safeExporter(failingOnceDelegate(firstCalls));
        SafeSpanExporter second = safeExporter(delegate(CompletableResultCode.ofSuccess()));

        try (AgentLogCapture capture = AgentLogCapture.installed()) {
            // when
            first.export(spansOfSize(1));
            second.export(spansOfSize(1));
            first.export(spansOfSize(1));

            // then
            assertThat(capture.events())
                    .filteredOn(event -> "SAFE_EXPORT_SUCCEEDED_AFTER_FAILURES".equals(event.eventName()))
                    .hasSize(1);
        }
    }

    @Test
    void lateSuccessStartedBeforeFailureDoesNotCloseRecoveryEpisode() {
        // given
        CompletableResultCode first = new CompletableResultCode();
        CompletableResultCode failure = new CompletableResultCode();
        CompletableResultCode recovery = new CompletableResultCode();
        SafeSpanExporter safe = safeExporter(new QueuedSpanExporter(
                step(first), step(failure), step(recovery)
        ));

        try (AgentLogCapture capture = AgentLogCapture.installed()) {
            safe.export(spansOfSize(1));
            safe.export(spansOfSize(1));

            // when
            failure.fail();
            first.succeed();

            // then
            assertThat(recoveryEvents(capture)).isEmpty();

            // when
            safe.export(spansOfSize(1));
            recovery.succeed();

            // then
            assertThat(recoveryEvents(capture))
                    .singleElement()
                    .satisfies(event -> assertThat(event.fields()).containsEntry("failures", "1"));
        }
    }

    @Test
    void successStartedBeforeNewestFailureDoesNotCloseRecoveryEpisode() {
        // given
        CompletableResultCode firstFailure = new CompletableResultCode();
        CompletableResultCode candidate = new CompletableResultCode();
        CompletableResultCode newestFailure = new CompletableResultCode();
        CompletableResultCode recovery = new CompletableResultCode();
        SafeSpanExporter safe = safeExporter(new QueuedSpanExporter(
                step(firstFailure), step(candidate), step(newestFailure), step(recovery)
        ));

        try (AgentLogCapture capture = AgentLogCapture.installed()) {
            safe.export(spansOfSize(1));
            firstFailure.fail();
            safe.export(spansOfSize(1));
            safe.export(spansOfSize(1));

            // when
            newestFailure.fail();
            candidate.succeed();

            // then
            assertThat(recoveryEvents(capture)).isEmpty();

            // when
            safe.export(spansOfSize(1));
            recovery.succeed();

            // then
            assertThat(recoveryEvents(capture))
                    .singleElement()
                    .satisfies(event -> assertThat(event.fields()).containsEntry("failures", "2"));
        }
    }

    @Test
    void lateFailureStartedBeforeCloseDoesNotOpenNewRecoveryEpisode() {
        // given
        CompletableResultCode lateFailure = new CompletableResultCode();
        CompletableResultCode failure = new CompletableResultCode();
        CompletableResultCode recovery = new CompletableResultCode();
        CompletableResultCode laterSuccess = new CompletableResultCode();
        SafeSpanExporter safe = safeExporter(new QueuedSpanExporter(
                step(lateFailure), step(failure), step(recovery), step(laterSuccess)
        ));

        try (AgentLogCapture capture = AgentLogCapture.installed()) {
            safe.export(spansOfSize(1));
            safe.export(spansOfSize(1));
            failure.fail();
            safe.export(spansOfSize(1));
            recovery.succeed();

            // when
            lateFailure.fail();
            safe.export(spansOfSize(1));
            laterSuccess.succeed();

            // then
            assertThat(recoveryEvents(capture))
                    .singleElement()
                    .satisfies(event -> assertThat(event.fields()).containsEntry("failures", "1"));
            assertThat(safe.getExportFailures()).isEqualTo(2L);
            assertThat(safe.getDroppedSpans()).isEqualTo(2L);
        }
    }

    @Test
    void lateFailureStartedBeforeOrdinarySuccessDoesNotOpenRecoveryEpisode() {
        // given
        CompletableResultCode lateFailure = new CompletableResultCode();
        CompletableResultCode success = new CompletableResultCode();
        CompletableResultCode laterSuccess = new CompletableResultCode();
        SafeSpanExporter safe = safeExporter(new QueuedSpanExporter(
                step(lateFailure), step(success), step(laterSuccess)
        ));

        try (AgentLogCapture capture = AgentLogCapture.installed()) {
            safe.export(spansOfSize(1));
            safe.export(spansOfSize(1));

            // when
            success.succeed();
            lateFailure.fail();
            safe.export(spansOfSize(1));
            laterSuccess.succeed();

            // then
            assertThat(recoveryEvents(capture)).isEmpty();
            assertThat(safe.getExportFailures()).isEqualTo(1L);
            assertThat(safe.getDroppedSpans()).isEqualTo(1L);
        }
    }

    @Test
    void recoveryEventReportsAllFailuresInClosedEpisodeOnlyOnce() {
        // given
        CompletableResultCode firstFailure = new CompletableResultCode();
        CompletableResultCode secondFailure = new CompletableResultCode();
        CompletableResultCode thirdFailure = new CompletableResultCode();
        CompletableResultCode recovery = new CompletableResultCode();
        CompletableResultCode laterSuccess = new CompletableResultCode();
        SafeSpanExporter safe = safeExporter(new QueuedSpanExporter(
                step(firstFailure), step(secondFailure), step(thirdFailure), step(recovery), step(laterSuccess)
        ));

        try (AgentLogCapture capture = AgentLogCapture.installed()) {
            // when
            safe.export(spansOfSize(1));
            firstFailure.fail();
            safe.export(spansOfSize(1));
            secondFailure.fail();
            safe.export(spansOfSize(1));
            thirdFailure.fail();
            safe.export(spansOfSize(1));
            recovery.succeed();
            safe.export(spansOfSize(1));
            laterSuccess.succeed();

            // then
            assertThat(recoveryEvents(capture))
                    .singleElement()
                    .satisfies(event -> assertThat(event.fields()).containsEntry("failures", "3"));
        }
    }

    @Test
    void synchronousExportExceptionOpensRecoveryEpisode() {
        // given
        AtomicInteger calls = new AtomicInteger();
        SafeSpanExporter safe = safeExporter(new SpanExporter() {
            @Override
            public CompletableResultCode export(Collection<SpanData> spans) {
                if (calls.getAndIncrement() == 0) {
                    throw new IllegalStateException("simulated synchronous export failure");
                }
                return CompletableResultCode.ofSuccess();
            }

            @Override
            public CompletableResultCode flush() {
                return CompletableResultCode.ofSuccess();
            }

            @Override
            public CompletableResultCode shutdown() {
                return CompletableResultCode.ofSuccess();
            }
        });

        try (AgentLogCapture capture = AgentLogCapture.installed()) {
            // when
            safe.export(spansOfSize(1));
            safe.export(spansOfSize(1));

            // then
            assertThat(recoveryEvents(capture))
                    .singleElement()
                    .satisfies(event -> assertThat(event.fields()).containsEntry("failures", "1"));
        }
    }

    @Test
    void nullExportResultOpensRecoveryEpisode() {
        // given
        SafeSpanExporter safe = safeExporter(new QueuedSpanExporter(
                step(null), step(CompletableResultCode.ofSuccess())
        ));

        try (AgentLogCapture capture = AgentLogCapture.installed()) {
            // when
            safe.export(spansOfSize(1));
            safe.export(spansOfSize(1));

            // then
            assertThat(recoveryEvents(capture))
                    .singleElement()
                    .satisfies(event -> assertThat(event.fields()).containsEntry("failures", "1"));
        }
    }

    @Test
    void partialSuccessClosesRecoveryEpisode() {
        // given
        SafeSpanExporter safe = safeExporter(new QueuedSpanExporter(
                step(CompletableResultCode.ofFailure()),
                step(CompletableResultCode.ofSuccess(), observation -> observation.recordRejected(1))
        ));

        try (AgentLogCapture capture = AgentLogCapture.installed()) {
            // when
            safe.export(spansOfSize(3));
            safe.export(spansOfSize(3));

            // then
            assertThat(recoveryEvents(capture)).hasSize(1);
            assertThat(safe.getPartialSuccessBatches()).isEqualTo(1L);
            assertThat(safe.getExportedSpans()).isEqualTo(2L);
            assertThat(safe.getRejectedSpans()).isEqualTo(1L);
        }
    }

    @Test
    void undeterminedSuccessClosesRecoveryEpisode() {
        // given
        SafeSpanExporter safe = safeExporter(new QueuedSpanExporter(
                step(CompletableResultCode.ofFailure()),
                step(CompletableResultCode.ofSuccess(), ExportResponseObservation::recordNotDetermined)
        ));

        try (AgentLogCapture capture = AgentLogCapture.installed()) {
            // when
            safe.export(spansOfSize(3));
            safe.export(spansOfSize(3));

            // then
            assertThat(recoveryEvents(capture)).hasSize(1);
            assertThat(safe.getAcceptedUnknownBatches()).isEqualTo(1L);
            assertThat(safe.getExportedSpans()).isZero();
        }
    }

    @Test
    void recoveryPublicationIsLimitedPerExporterInstance() {
        // given
        AtomicLong clock = new AtomicLong();
        SafeSpanExporter safe = safeExporter(new QueuedSpanExporter(
                step(CompletableResultCode.ofFailure()), step(CompletableResultCode.ofSuccess()),
                step(CompletableResultCode.ofFailure()), step(CompletableResultCode.ofSuccess()),
                step(CompletableResultCode.ofFailure()), step(CompletableResultCode.ofSuccess())
        ), clock::get);

        try (AgentLogCapture capture = AgentLogCapture.installed()) {
            // when
            safe.export(spansOfSize(1));
            safe.export(spansOfSize(1));
            safe.export(spansOfSize(1));
            safe.export(spansOfSize(1));
            clock.addAndGet(Duration.ofMinutes(1L).toNanos());
            safe.export(spansOfSize(1));
            safe.export(spansOfSize(1));

            // then
            assertThat(recoveryEvents(capture))
                    .hasSize(2)
                    .allSatisfy(event -> assertThat(event.fields()).containsEntry("failures", "1"));
        }
    }

    @Test
    void filteredRecoveryConsumesPublicationPermitWithoutKeepingEpisodeOpen() {
        // given
        AtomicLong clock = new AtomicLong();
        SafeSpanExporter safe = safeExporter(new QueuedSpanExporter(
                step(CompletableResultCode.ofFailure()), step(CompletableResultCode.ofSuccess()),
                step(CompletableResultCode.ofSuccess()),
                step(CompletableResultCode.ofFailure()), step(CompletableResultCode.ofSuccess()),
                step(CompletableResultCode.ofFailure()), step(CompletableResultCode.ofSuccess())
        ), clock::get);

        try (AgentLogCapture capture = AgentLogCapture.installed()) {
            capture.applyLevel(AgentLogLevel.WARN);

            // when
            safe.export(spansOfSize(1));
            safe.export(spansOfSize(1));
            capture.applyLevel(AgentLogLevel.INFO);
            safe.export(spansOfSize(1));
            safe.export(spansOfSize(1));
            safe.export(spansOfSize(1));

            // then
            assertThat(recoveryEvents(capture)).isEmpty();

            // when
            clock.addAndGet(Duration.ofMinutes(1L).toNanos());
            safe.export(spansOfSize(1));
            safe.export(spansOfSize(1));

            // then
            assertThat(recoveryEvents(capture))
                    .singleElement()
                    .satisfies(event -> assertThat(event.fields()).containsEntry("failures", "1"));
        }
    }

    @Test
    void exportGateSuccessDoesNotCloseRecoveryEpisode() {
        // given
        TracingSettingsSource policy = TracingSettingsSourceTestFixtures.boundDefaults(AgentInstanceId.random());
        SafeSpanExporter safe = safeExporter(new QueuedSpanExporter(
                step(CompletableResultCode.ofFailure()), step(CompletableResultCode.ofSuccess())
        ), policy);

        try (AgentLogCapture capture = AgentLogCapture.installed()) {
            safe.export(spansOfSize(1));
            AcceptedPolicyMutationAccess.applyExporter(policy, false);

            // when
            safe.export(spansOfSize(1));

            // then
            assertThat(recoveryEvents(capture)).isEmpty();

            // when
            AcceptedPolicyMutationAccess.applyExporter(policy, true);
            safe.export(spansOfSize(1));

            // then
            assertThat(recoveryEvents(capture)).hasSize(1);
        }
    }

    @Test
    void boundaryDroppedEmptyBatchDoesNotCloseRecoveryEpisode() {
        // given
        ScrubbingRuntime runtime = enabledRuntime("email");
        SafeSpanExporter safe = safeExporter(
                new QueuedSpanExporter(
                        step(CompletableResultCode.ofFailure()), step(CompletableResultCode.ofSuccess())
                ),
                TracingSettingsSourceTestFixtures.boundDefaults(AgentInstanceId.random()),
                runtime
        );
        SpanData normal = ExporterBoundaryTestSupport.baseSpan("normal").build();
        SpanData poisoned = new DelegatingSpanData(ExporterBoundaryTestSupport.baseSpan("poisoned").build()) {
            @Override
            public Attributes getAttributes() {
                throw new IllegalStateException("simulated corruption");
            }
        };

        try (AgentLogCapture capture = AgentLogCapture.installed()) {
            safe.export(List.of(normal));

            // when
            CompletableResultCode dropped = safe.export(List.of(poisoned));

            // then
            assertThat(dropped.isSuccess()).isTrue();
            assertThat(recoveryEvents(capture)).isEmpty();

            // when
            safe.export(List.of(normal));

            // then
            assertThat(recoveryEvents(capture)).hasSize(1);
        }
    }

    @Test
    void acceptedCountDeliveryFailureLeavesRecoveryEpisodeClosed() throws Exception {
        // given
        SelectiveFailureOutputStream output = new SelectiveFailureOutputStream(
                "\"event_name\":\"EXPORT_ACCEPTED_SPAN_COUNT_UNAVAILABLE\""
        );
        AtomicLong clock = new AtomicLong();
        SafeSpanExporter safe = safeExporter(new QueuedSpanExporter(
                step(CompletableResultCode.ofFailure()),
                step(CompletableResultCode.ofSuccess(), ExportResponseObservation::recordNotDetermined),
                step(CompletableResultCode.ofFailure()),
                step(CompletableResultCode.ofSuccess())
        ), clock::get);

        try (AutoCloseable ignored = AgentLoggingTestSupport.install(output)) {
            AgentLoggingRuntime.shared().applyLevel(AgentLogLevel.TRACE);

            // when
            safe.export(spansOfSize(1));
            safe.export(spansOfSize(1));
            clock.addAndGet(Duration.ofMinutes(1L).toNanos());
            safe.export(spansOfSize(1));
            safe.export(spansOfSize(1));
        }

        // then
        assertThat(output.failures()).isEqualTo(1);
        assertThat(output.contents())
                .contains("\"event_name\":\"SAFE_EXPORT_SUCCEEDED_AFTER_FAILURES\"")
                .doesNotContain("\"event_name\":\"EXPORT_COMPLETION_CALLBACK_FAILED\"");
        assertThat(output.contents().lines()
                .filter(line -> line.contains("\"event_name\":\"SAFE_EXPORT_SUCCEEDED_AFTER_FAILURES\"")))
                .hasSize(2)
                .allMatch(line -> line.contains("\"failures\":\"1\""));
        assertThat(safe.getAcceptedUnknownBatches()).isEqualTo(1L);
    }

    @Test
    void fatalAcceptedCountLoggingFailureCannotUndoRecoveryClosure() throws Exception {
        // given
        SelectiveFailureOutputStream output = new SelectiveFailureOutputStream(
                "\"event_name\":\"EXPORT_ACCEPTED_SPAN_COUNT_UNAVAILABLE\"",
                true
        );
        AtomicLong clock = new AtomicLong();
        SafeSpanExporter safe = safeExporter(new QueuedSpanExporter(
                step(CompletableResultCode.ofFailure()),
                step(CompletableResultCode.ofSuccess(), ExportResponseObservation::recordNotDetermined),
                step(CompletableResultCode.ofFailure()),
                step(CompletableResultCode.ofSuccess())
        ), clock::get);

        try (AutoCloseable ignored = AgentLoggingTestSupport.install(output)) {
            AgentLoggingRuntime.shared().applyLevel(AgentLogLevel.TRACE);

            // when
            safe.export(spansOfSize(1));
            assertThatThrownBy(() -> safe.export(spansOfSize(1)))
                    .isInstanceOf(LinkageError.class)
                    .hasMessage("simulated fatal log delivery failure");
            clock.addAndGet(Duration.ofMinutes(1L).toNanos());
            safe.export(spansOfSize(1));
            safe.export(spansOfSize(1));
        }

        // then
        assertThat(output.failures()).isEqualTo(1);
        assertThat(output.contents().lines()
                .filter(line -> line.contains("\"event_name\":\"SAFE_EXPORT_SUCCEEDED_AFTER_FAILURES\"")))
                .hasSize(2)
                .allMatch(line -> line.contains("\"failures\":\"1\""));
        assertThat(safe.getAcceptedUnknownBatches()).isEqualTo(1L);
        assertThat(safe.getExportFailures()).isEqualTo(2L);
    }

    @Test
    void sequenceFencingSurvivesSignedLongRollover() throws Exception {
        // given
        SafeSpanExporter safe = safeExporter(new QueuedSpanExporter(
                step(CompletableResultCode.ofFailure()), step(CompletableResultCode.ofSuccess())
        ));
        Field operationSeq = SafeSpanExporter.class.getDeclaredField("operationSeq");
        operationSeq.setAccessible(true);
        operationSeq.setLong(safe, Long.MAX_VALUE - 1L);

        try (AgentLogCapture capture = AgentLogCapture.installed()) {
            // when
            safe.export(spansOfSize(1));
            safe.export(spansOfSize(1));

            // then
            assertThat(recoveryEvents(capture))
                    .singleElement()
                    .satisfies(event -> assertThat(event.fields()).containsEntry("failures", "1"));
        }
    }

    @Test
    void shutdownFailureIsNotSuppressedByRoutineFailureStorm() {
        // given
        SafeSpanExporter safe = safeExporter(new SpanExporter() {
            @Override
            public CompletableResultCode export(Collection<SpanData> spans) {
                return CompletableResultCode.ofFailure();
            }

            @Override
            public CompletableResultCode flush() {
                return CompletableResultCode.ofSuccess();
            }

            @Override
            public CompletableResultCode shutdown() {
                return CompletableResultCode.ofFailure();
            }
        });

        try (AgentLogCapture capture = AgentLogCapture.installed()) {
            for (int attempt = 0; attempt < 20; attempt++) {
                safe.export(spansOfSize(1));
            }

            // when
            safe.shutdown();

            // then
            assertThat(capture.events())
                    .filteredOn(event -> "SAFE_EXPORT_RETURNED_FAILURE".equals(event.eventName()))
                    .hasSizeLessThan(20);
            assertThat(capture.events())
                    .filteredOn(event -> "SAFE_SHUTDOWN_RETURNED_FAILURE".equals(event.eventName()))
                    .hasSize(1);
        }
    }

    @Test
    void diagnosticEventsSurviveExportFailureStorm() {
        // given
        AtomicInteger exports = new AtomicInteger();
        SafeSpanExporter safe = safeExporter(new SpanExporter() {
            @Override
            public CompletableResultCode export(Collection<SpanData> spans) {
                if (exports.incrementAndGet() <= 20) {
                    return CompletableResultCode.ofFailure();
                }

                Objects.requireNonNull(ExportResponseScope.current(), "observation").recordNotDetermined();
                return CompletableResultCode.ofSuccess();
            }

            @Override
            public CompletableResultCode flush() {
                return CompletableResultCode.ofFailure();
            }

            @Override
            public CompletableResultCode shutdown() {
                return CompletableResultCode.ofSuccess();
            }
        });

        try (AgentLogCapture capture = AgentLogCapture.installed()) {
            for (int attempt = 0; attempt < 20; attempt++) {
                safe.export(spansOfSize(1));
            }

            // when
            safe.flush();
            safe.export(spansOfSize(1));

            // then
            assertThat(named(capture, "SAFE_EXPORT_RETURNED_FAILURE")).hasSizeLessThan(20);
            assertThat(named(capture, "SAFE_FLUSH_RETURNED_FAILURE")).hasSize(1);
            assertThat(named(capture, "EXPORT_ACCEPTED_SPAN_COUNT_UNAVAILABLE")).hasSize(1);
            assertThat(safe.getAcceptedUnknownBatches()).isEqualTo(1L);
        }
    }

    @Test
    void lateCompletionCallbackFailureDoesNotBlockResultAndIsThrottled() {
        // given
        Deque<CompletableResultCode> results = new ArrayDeque<>();
        SafeSpanExporter safe = safeExporter(new SpanExporter() {
            @Override
            public CompletableResultCode export(Collection<SpanData> spans) {
                return results.removeFirst();
            }

            @Override
            public CompletableResultCode flush() {
                return CompletableResultCode.ofSuccess();
            }

            @Override
            public CompletableResultCode shutdown() {
                return CompletableResultCode.ofSuccess();
            }
        }, () -> {
            throw new IllegalStateException("simulated completion defect");
        });

        AtomicInteger laterActions = new AtomicInteger();
        AtomicInteger succeededResults = new AtomicInteger();

        try (AgentLogCapture capture = AgentLogCapture.installed()) {
            // when
            for (int attempt = 0; attempt < 20; attempt++) {
                results.addLast(CompletableResultCode.ofFailure());
                safe.export(spansOfSize(1));

                CompletableResultCode pending = new CompletableResultCode();
                results.addLast(pending);
                CompletableResultCode result = safe.export(spansOfSize(1));
                result.whenComplete(laterActions::incrementAndGet);
                pending.succeed();
                if (result.isDone() && result.isSuccess()) {
                    succeededResults.incrementAndGet();
                }
            }

            // then
            assertThat(succeededResults).hasValue(20);
            assertThat(laterActions).hasValue(20);
            assertThat(named(capture, "EXPORT_COMPLETION_CALLBACK_FAILED"))
                    .isNotEmpty()
                    .hasSizeLessThan(20);
        }
    }

    @Test
    @DisplayName("Исключение делегата изолировано: наружу ofFailure(), исключение не пробрасывается")
    void delegateExceptionIsIsolated() {
        // given
        SafeSpanExporter safe = safeExporter(new SpanExporter() {
            @Override
            public CompletableResultCode export(Collection<SpanData> spans) {
                throw new RuntimeException("simulated transport failure");
            }

            @Override
            public CompletableResultCode flush() {
                return CompletableResultCode.ofSuccess();
            }

            @Override
            public CompletableResultCode shutdown() {
                return CompletableResultCode.ofSuccess();
            }
        });

        // when
        CompletableResultCode result = safe.export(spansOfSize(4));

        // then
        assertThat(result.isSuccess()).isFalse();
        assertThat(safe.getExportFailures()).isEqualTo(1);
        assertThat(safe.getDroppedSpans()).isEqualTo(4);
    }

    @Test
    @DisplayName("Делегат вернул null: трактуем как failure без NPE")
    void nullResultTreatedAsFailure() {
        // given
        SafeSpanExporter safe = safeExporter(delegate(null));

        // when
        CompletableResultCode result = safe.export(spansOfSize(2));

        // then
        assertThat(result.isSuccess()).isFalse();
        assertThat(safe.getExportFailures()).isEqualTo(1);
        assertThat(safe.getDroppedSpans()).isEqualTo(2);
    }

    @Test
    @DisplayName("Семантика счётчиков: два failed-batch'а по 3 → failures=2, dropped=6")
    void countersDistinguishBatchesFromSpans() {
        // given
        SafeSpanExporter safe = safeExporter(delegate(CompletableResultCode.ofFailure()));

        // when
        safe.export(spansOfSize(3));
        safe.export(spansOfSize(3));

        // then
        assertThat(safe.getExportFailures()).as("failures — это batches").isEqualTo(2);
        assertThat(safe.getDroppedSpans()).as("dropped — это spans").isEqualTo(6);
    }

    @Test
    @DisplayName("flush(): исключение делегата изолировано, инкремент flushFailures")
    void flushExceptionIsIsolated() {
        // given
        SafeSpanExporter safe = safeExporter(new SpanExporter() {
            @Override
            public CompletableResultCode export(Collection<SpanData> spans) {
                return CompletableResultCode.ofSuccess();
            }

            @Override
            public CompletableResultCode flush() {
                throw new RuntimeException("flush boom");
            }

            @Override
            public CompletableResultCode shutdown() {
                return CompletableResultCode.ofSuccess();
            }
        });

        // when
        CompletableResultCode result = safe.flush();

        // then
        assertThat(result.isSuccess()).isFalse();
        assertThat(safe.getFlushFailures()).isEqualTo(1);
    }

    @Test
    @DisplayName("shutdown(): исключение делегата изолировано, инкремент shutdownFailures")
    void shutdownExceptionIsIsolated() {
        // given
        SafeSpanExporter safe = safeExporter(new SpanExporter() {
            @Override
            public CompletableResultCode export(Collection<SpanData> spans) {
                return CompletableResultCode.ofSuccess();
            }

            @Override
            public CompletableResultCode flush() {
                return CompletableResultCode.ofSuccess();
            }

            @Override
            public CompletableResultCode shutdown() {
                throw new RuntimeException("shutdown boom");
            }
        });

        // when
        CompletableResultCode result = safe.shutdown();

        // then
        assertThat(result.isSuccess()).isFalse();
        assertThat(safe.getShutdownFailures()).isEqualTo(1);
    }

    @Test
    void flushNullResultLogsExporterTypeWithoutRawTransportData() {
        // given
        LifecycleSpanExporter delegate = new LifecycleSpanExporter(
                null,
                CompletableResultCode.ofSuccess()
        );
        try (SafeSpanExporter safe = new SafeSpanExporter(
                delegate,
                TracingSettingsSourceTestFixtures.boundDefaults(AgentInstanceId.random()),
                ScrubbingRuntimeTestFixtures.disabled(),
                new RateLimitedLogger(SafeSpanExporterTest.class),
                new RateLimitedLogger(SafeSpanExporterTest.class),
                System::nanoTime);
             AgentLogCapture logs = AgentLogCapture.installed()) {
            // when
            CompletableResultCode result = safe.flush();

            // then
            assertThat(result.isSuccess()).isFalse();
            assertThat(safe.getFlushFailures()).isEqualTo(1);
            assertThat(logs.events())
                    .filteredOn(event -> "SAFE_FLUSH_RETURNED_NULL".equals(event.eventName()))
                    .singleElement()
                    .satisfies(event -> assertThat(event.fields())
                            .containsOnly(Map.entry("exporterType", "LifecycleSpanExporter")));
            assertThat(logs.rendered()).doesNotContain("endpoint", "transport message");
        }
    }

    @Test
    void shutdownAsyncFailureIsObservedAndLogged() {
        // given
        CompletableResultCode pendingShutdown = new CompletableResultCode();
        LifecycleSpanExporter delegate = new LifecycleSpanExporter(
                CompletableResultCode.ofSuccess(),
                pendingShutdown
        );
        try (SafeSpanExporter safe = new SafeSpanExporter(
                delegate,
                TracingSettingsSourceTestFixtures.boundDefaults(AgentInstanceId.random()),
                ScrubbingRuntimeTestFixtures.disabled(),
                new RateLimitedLogger(SafeSpanExporterTest.class),
                new RateLimitedLogger(SafeSpanExporterTest.class),
                System::nanoTime);
             AgentLogCapture logs = AgentLogCapture.installed()) {
            CompletableResultCode result = safe.shutdown();

            // when
            pendingShutdown.fail();

            // then
            assertThat(result.isSuccess()).isFalse();
            assertThat(safe.getShutdownFailures()).isEqualTo(1);
            assertThat(logs.events())
                    .filteredOn(event -> "SAFE_SHUTDOWN_RETURNED_FAILURE".equals(event.eventName()))
                    .singleElement()
                    .satisfies(event -> assertThat(event.fields())
                            .containsOnly(Map.entry("exporterType", "LifecycleSpanExporter")));
            assertThat(logs.rendered()).doesNotContain("endpoint", "transport message");
        }
    }

    @Test
    @DisplayName("metricsSnapshot содержит стабильный набор ключей")
    void metricsSnapshotHasStableKeys() {
        // given
        SafeSpanExporter safe = safeExporter(delegate(CompletableResultCode.ofSuccess()));
        safe.export(spansOfSize(1));

        // when
        Map<String, Long> snapshot = safe.metricsSnapshot();

        // then
        assertThat(snapshot).containsKeys(
                "export_batches", "export_batch_failures", "exported_spans", "transport_dropped_spans",
                "flush_failures", "shutdown_failures", "suppressed_spans_export_disabled");
        assertThat(snapshot.get("exported_spans")).isEqualTo(1L);
    }

    @Test
    @DisplayName("Export-gate (Фаза 14): выключенный экспорт отбрасывает batch, делегат не вызывается, success")
    void exportGateDropsBatchWithoutCallingDelegate() {
        // given
        AtomicInteger delegateCalls = new AtomicInteger();
        TracingSettingsSource access = TracingSettingsSourceTestFixtures.boundDefaults(AgentInstanceId.random());
        SafeSpanExporter safe = safeExporter(new SpanExporter() {
            @Override
            public CompletableResultCode export(Collection<SpanData> spans) {
                delegateCalls.incrementAndGet();
                return CompletableResultCode.ofSuccess();
            }

            @Override
            public CompletableResultCode flush() {
                return CompletableResultCode.ofSuccess();
            }

            @Override
            public CompletableResultCode shutdown() {
                return CompletableResultCode.ofSuccess();
            }
        }, access);

        // when
        AcceptedPolicyMutationAccess.applyExporter(access, false);
        CompletableResultCode gated = safe.export(spansOfSize(4));

        // then
        assertThat(gated.isSuccess()).as("gate возвращает success, BSP не ретраит").isTrue();
        assertThat(delegateCalls.get()).as("делегат не вызывается").isZero();
        assertThat(safe.getGatedSpans()).isEqualTo(4);
        assertThat(safe.metricsSnapshot().get("suppressed_spans_export_disabled")).isEqualTo(4L);
        assertThat(safe.getExportBatches()).as("gated не считается батчем экспорта").isZero();
        assertThat(safe.getExportFailures()).as("gate — не отказ транспорта").isZero();
        assertThat(safe.getDroppedSpans()).isZero();

        // when
        AcceptedPolicyMutationAccess.applyExporter(access, true);
        safe.export(spansOfSize(2));

        // then
        assertThat(delegateCalls.get()).as("после включения делегат снова вызывается").isEqualTo(1);
        assertThat(safe.getExportedSpans()).isEqualTo(2);
    }

    @Test
    @DisplayName("exporterEnabled=false does not run boundary scrubbing and accounts the original gated batch")
    void exportGateDoesNotInvokeBoundaryWhenDisabled() {
        // given
        ScrubbingRuntime runtime = enabledRuntime("password");
        TracingSettingsSource access = TracingSettingsSourceTestFixtures.boundDefaults(AgentInstanceId.random());
        AtomicInteger delegateCalls = new AtomicInteger();
        SafeSpanExporter safe = safeExporter(new SpanExporter() {
            @Override
            public CompletableResultCode export(Collection<SpanData> spans) {
                delegateCalls.incrementAndGet();
                return CompletableResultCode.ofSuccess();
            }

            @Override
            public CompletableResultCode flush() {
                return CompletableResultCode.ofSuccess();
            }

            @Override
            public CompletableResultCode shutdown() {
                return CompletableResultCode.ofSuccess();
            }
        }, access, runtime);

        // when
        AcceptedPolicyMutationAccess.applyExporter(access, false);
        SpanData span = ExporterBoundaryTestSupport.baseSpan("op")
                .setEvents(List.of(EventData.create(
                        1L, "db.query", Attributes.of(AttributeKey.stringKey("db.password"), "supersecret"))))
                .build();
        CompletableResultCode gated = safe.export(List.of(span, span));

        // then
        assertThat(gated.isSuccess()).isTrue();
        assertThat(delegateCalls.get()).isZero();
        assertThat(safe.getGatedSpans()).isEqualTo(2);
        assertThat(runtime.boundaryMetrics().exporterInvocations())
                .as("gated export must not enter the boundary")
                .isZero();
        assertThat(runtime.boundaryMetrics().inputSpans()).isZero();
    }

    @Test
    @DisplayName("SP-04: InterruptedException — флаг прерывания восстановлен, возвращается failure")
    void restoresInterruptFlagWhenDelegateThrowsInterruptedException() {
        // given
        SafeSpanExporter safe = safeExporter(new SpanExporter() {
            @Override
            public CompletableResultCode export(Collection<SpanData> spans) {
                sneakyThrow(new InterruptedException("simulated interrupt during export"));
                return null; // unreachable
            }

            @Override
            public CompletableResultCode flush() {
                return CompletableResultCode.ofSuccess();
            }

            @Override
            public CompletableResultCode shutdown() {
                return CompletableResultCode.ofSuccess();
            }
        });

        //noinspection ResultOfMethodCallIgnored -- вызов чистит флаг, возврат намеренно не нужен
        Thread.interrupted(); // гарантируем чистый флаг до вызова

        try {
            // when
            CompletableResultCode result = safe.export(spansOfSize(1));

            // then
            assertThat(result).isNotNull();
            assertThat(result.isSuccess()).isFalse();
            assertThat(Thread.currentThread().isInterrupted())
                    .as("propagateIfFatal должен восстановить флаг прерывания")
                    .isTrue();
        } finally {
            //noinspection ResultOfMethodCallIgnored
            Thread.interrupted(); // очищаем флаг, чтобы не загрязнять другие тесты
        }
    }

    @Test
    @DisplayName("SP-04: LinkageError (fatal) пробрасывается наружу, не поглощается")
    void propagatesFatalThrowableAccordingToPlatformThrowablesPolicy() {
        // given
        SafeSpanExporter safe = safeExporter(new SpanExporter() {
            @Override
            public CompletableResultCode export(Collection<SpanData> spans) {
                throw new LinkageError("simulated fatal linkage error");
            }

            @Override
            public CompletableResultCode flush() {
                return CompletableResultCode.ofSuccess();
            }

            @Override
            public CompletableResultCode shutdown() {
                return CompletableResultCode.ofSuccess();
            }
        });

        // when/then
        assertThatThrownBy(() -> safe.export(spansOfSize(1)))
                .isInstanceOf(LinkageError.class)
                .hasMessage("simulated fatal linkage error");
    }

    @Test
    @DisplayName("SP-04: flush() null-результат → ofFailure() без NPE, flushFailures растёт")
    void flushNullResultTreatedAsFailureAndIncrementsFailureCounter() {
        // given
        SafeSpanExporter safe = safeExporter(new SpanExporter() {
            @Override
            public CompletableResultCode export(Collection<SpanData> spans) {
                return CompletableResultCode.ofSuccess();
            }

            @Override
            public CompletableResultCode flush() {
                return null;
            }

            @Override
            public CompletableResultCode shutdown() {
                return CompletableResultCode.ofSuccess();
            }
        });

        // when
        CompletableResultCode result = safe.flush();

        // then
        assertThat(result).isNotNull();
        assertThat(result.isSuccess()).isFalse();
        assertThat(safe.getFlushFailures()).isEqualTo(1);
    }

    @Test
    @DisplayName("SP-04: shutdown() null-результат → ofFailure() без NPE, shutdownFailures растёт")
    void shutdownNullResultTreatedAsFailureAndIncrementsFailureCounter() {
        // given
        SafeSpanExporter safe = safeExporter(new SpanExporter() {
            @Override
            public CompletableResultCode export(Collection<SpanData> spans) {
                return CompletableResultCode.ofSuccess();
            }

            @Override
            public CompletableResultCode flush() {
                return CompletableResultCode.ofSuccess();
            }

            @Override
            public CompletableResultCode shutdown() {
                return null;
            }
        });

        // when
        CompletableResultCode result = safe.shutdown();

        // then
        assertThat(result).isNotNull();
        assertThat(result.isSuccess()).isFalse();
        assertThat(safe.getShutdownFailures()).isEqualTo(1);
    }

    @Test
    @DisplayName("SP-04: metricsSnapshot корректно отражает export/flush/shutdown failures")
    void metricsSnapshotReflectsAllFailureCounters() {
        // given
        SafeSpanExporter safe = safeExporter(new SpanExporter() {
            @Override
            public CompletableResultCode export(Collection<SpanData> spans) {
                throw new RuntimeException("export boom");
            }

            @Override
            public CompletableResultCode flush() {
                throw new RuntimeException("flush boom");
            }

            @Override
            public CompletableResultCode shutdown() {
                throw new RuntimeException("shutdown boom");
            }
        });

        // when
        safe.export(spansOfSize(3));
        safe.flush();
        safe.shutdown();
        Map<String, Long> snapshot = safe.metricsSnapshot();

        // then
        assertThat(snapshot.get(JmxWireContract.KEY_SAFE_EXPORTER_METRICS_EXPORT_BATCH_FAILURES))
                .as("export exception → export_batch_failures=1").isEqualTo(1L);
        assertThat(snapshot.get(JmxWireContract.KEY_SAFE_EXPORTER_METRICS_TRANSPORT_DROPPED_SPANS))
                .as("3 spans dropped on export failure").isEqualTo(3L);
        assertThat(snapshot.get(JmxWireContract.KEY_SAFE_EXPORTER_METRICS_FLUSH_FAILURES))
                .as("flush exception → flush_failures=1").isEqualTo(1L);
        assertThat(snapshot.get(JmxWireContract.KEY_SAFE_EXPORTER_METRICS_SHUTDOWN_FAILURES))
                .as("shutdown exception → shutdown_failures=1").isEqualTo(1L);
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void sneakyThrow(Throwable e) throws E {
        throw (E) e;
    }

    private static SpanExporter delegate(CompletableResultCode exportResult) {
        return new SpanExporter() {
            @Override
            public CompletableResultCode export(Collection<SpanData> spans) {
                return exportResult;
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
    }

    private static SpanExporter failingOnceDelegate(AtomicInteger calls) {
        return new SpanExporter() {
            @Override
            public CompletableResultCode export(Collection<SpanData> spans) {
                return calls.getAndIncrement() == 0
                        ? CompletableResultCode.ofFailure()
                        : CompletableResultCode.ofSuccess();
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
    }

    private static SpanExporter respondingDelegate(CompletableResultCode exportResult,
                                                    Consumer<ExportResponseObservation> response) {
        return new SpanExporter() {
            @Override
            public CompletableResultCode export(Collection<SpanData> spans) {
                response.accept(Objects.requireNonNull(ExportResponseScope.current(),
                        "export operation must be observable"));
                return exportResult;
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
    }

    private static List<CapturedLogEvent> named(AgentLogCapture capture, String eventName) {
        return capture.events().stream()
                .filter(event -> eventName.equals(event.eventName()))
                .toList();
    }

    private static List<CapturedLogEvent> recoveryEvents(AgentLogCapture capture) {
        return capture.events().stream()
                .filter(event -> "SAFE_EXPORT_SUCCEEDED_AFTER_FAILURES".equals(event.eventName()))
                .toList();
    }

    private static ExportStep step(CompletableResultCode result) {
        return step(result, observation -> {
        });
    }

    private static ExportStep step(CompletableResultCode result,
                                   Consumer<ExportResponseObservation> response) {
        return new ExportStep(result, response);
    }

    @Test
    @DisplayName("Partial success: exported += N - R, rejected += R, partial-batch учтён")
    void partialSuccessCreditsOnlyAcceptedSpans() {
        // given
        SafeSpanExporter safe = safeExporter(respondingDelegate(CompletableResultCode.ofSuccess(),
                observation -> observation.recordRejected(1)));

        // when
        CompletableResultCode result = safe.export(spansOfSize(3));

        // then
        assertThat(result.isSuccess()).isTrue();
        assertThat(safe.getExportedSpans()).as("принято N - R").isEqualTo(2);
        assertThat(safe.getRejectedSpans()).isEqualTo(1);
        assertThat(safe.getPartialSuccessBatches()).isEqualTo(1);
        assertThat(safe.getAcceptedUnknownBatches()).isZero();
        assertThat(safe.getDroppedSpans()).isZero();
    }

    @Test
    @DisplayName("Непригодный ответ: принятые span'ы не начисляются и batch помечен как неизвестный")
    void undeterminedResponseDoesNotCreditAcceptedSpans() {
        // given
        SafeSpanExporter safe = safeExporter(respondingDelegate(CompletableResultCode.ofSuccess(),
                ExportResponseObservation::recordNotDetermined));

        // when
        CompletableResultCode result = safe.export(spansOfSize(3));

        // then
        assertThat(result.isSuccess()).isTrue();
        assertThat(safe.getExportedSpans()).isZero();
        assertThat(safe.getRejectedSpans()).isZero();
        assertThat(safe.getPartialSuccessBatches()).isZero();
        assertThat(safe.getAcceptedUnknownBatches()).isEqualTo(1);
    }

    @Test
    @DisplayName("rejected_spans больше размера batch'а не порождает принятых span'ов")
    void rejectedSpansAboveBatchSizeIsNotCredited() {
        // given
        SafeSpanExporter safe = safeExporter(respondingDelegate(CompletableResultCode.ofSuccess(),
                observation -> observation.recordRejected(7)));

        // when
        safe.export(spansOfSize(3));

        // then
        assertThat(safe.getExportedSpans()).isZero();
        assertThat(safe.getRejectedSpans()).isZero();
        assertThat(safe.getAcceptedUnknownBatches()).isEqualTo(1);
    }

    @Test
    @DisplayName("Ответ без отклонений начисляет весь batch")
    void fullyAcceptedResponseCreditsWholeBatch() {
        // given
        SafeSpanExporter safe = safeExporter(respondingDelegate(CompletableResultCode.ofSuccess(),
                ExportResponseObservation::recordAllAccepted));

        // when
        safe.export(spansOfSize(4));

        // then
        assertThat(safe.getExportedSpans()).isEqualTo(4);
        assertThat(safe.getRejectedSpans()).isZero();
        assertThat(safe.getPartialSuccessBatches()).isZero();
    }

    @Test
    @DisplayName("Неуспешный транспорт не начисляет принятые span'ы даже после наблюдения")
    void failedTransportDoesNotCreditObservedResponse() {
        // given
        SafeSpanExporter safe = safeExporter(respondingDelegate(CompletableResultCode.ofFailure(),
                observation -> observation.recordRejected(1)));

        // when
        safe.export(spansOfSize(3));

        // then
        assertThat(safe.getExportedSpans()).isZero();
        assertThat(safe.getDroppedSpans()).isEqualTo(3);
        assertThat(safe.getExportFailures()).isEqualTo(1);
        assertThat(safe.getRejectedSpans()).isZero();
    }

    @Test
    @DisplayName("Снимок метрик публикует счётчики отклонений")
    void metricsSnapshotPublishesRejectionCounters() {
        // given
        SafeSpanExporter safe = safeExporter(respondingDelegate(CompletableResultCode.ofSuccess(),
                observation -> observation.recordRejected(2)));

        // when
        safe.export(spansOfSize(5));
        Map<String, Long> snapshot = safe.metricsSnapshot();

        // then
        assertThat(snapshot)
                .containsEntry(JmxWireContract.KEY_SAFE_EXPORTER_METRICS_EXPORTED_SPANS, 3L)
                .containsEntry(JmxWireContract.KEY_SAFE_EXPORTER_METRICS_REJECTED_SPANS, 2L)
                .containsEntry(JmxWireContract.KEY_SAFE_EXPORTER_METRICS_PARTIAL_SUCCESS_BATCHES, 1L)
                .containsEntry(JmxWireContract.KEY_SAFE_EXPORTER_METRICS_ACCEPTED_UNKNOWN_BATCHES, 0L);
    }

    @Test
    @DisplayName("Позднее завершение делегата после processor timeout всё ещё начисляет принятые span'ы")
    void lateDelegateCompletionStillCreditsAcceptedSpans() {
        // given
        CompletableResultCode pending = new CompletableResultCode();
        SafeSpanExporter safe = safeExporter(respondingDelegate(pending,
                observation -> observation.recordRejected(1)));

        // when
        CompletableResultCode result = safe.export(spansOfSize(3));

        // then
        assertThat(result.isDone()).as("processor увидит незавершённый export и зафиксирует timeout").isFalse();
        assertThat(safe.getExportedSpans()).isZero();
        assertThat(safe.getRejectedSpans()).isZero();

        // when
        pending.succeed();

        // then
        assertThat(safe.getExportedSpans()).as("принято N - R после позднего ответа").isEqualTo(2);
        assertThat(safe.getRejectedSpans()).isEqualTo(1);
        assertThat(safe.getPartialSuccessBatches()).isEqualTo(1);
        assertThat(safe.getExportFailures()).as("поздний успех не считается транспортной ошибкой").isZero();
        assertThat(safe.getDroppedSpans()).isZero();
    }

    @Test
    @DisplayName("Позднее падение делегата после processor timeout учитывается один раз как транспортная ошибка")
    void lateDelegateFailureIsCountedOnceAsTransportFailure() {
        // given
        CompletableResultCode pending = new CompletableResultCode();
        SafeSpanExporter safe = safeExporter(delegate(pending));

        // when
        safe.export(spansOfSize(3));
        pending.fail();

        // then
        assertThat(safe.getExportFailures()).isEqualTo(1);
        assertThat(safe.getDroppedSpans()).isEqualTo(3);
        assertThat(safe.getExportedSpans()).isZero();
        assertThat(safe.getAcceptedUnknownBatches()).isZero();
    }

    @SuppressWarnings("unused")
    private static List<SpanData> emptySpans() {
        return List.of();
    }

    private static ScrubbingRuntime enabledRuntime(String... ruleNames) {
        List<SpanAttributeScrubbingRule> rules = new ArrayList<>();
        for (String name : ruleNames) {
            rules.add(BuiltInSpanAttributeScrubbingRules.resolve(name));
        }
        return ExporterBoundaryTestSupport.runtimeOf(rules);
    }

    @Test
    @DisplayName("Boundary scrubbing runs before the delegate sees the batch: event attributes are already scrubbed")
    void delegateReceivesTheBoundaryScrubbedBatch() {
        // given
        ScrubbingRuntime runtime = enabledRuntime("password");
        List<SpanData> captured = new ArrayList<>();
        SafeSpanExporter safe = safeExporter(new SpanExporter() {
            @Override
            public CompletableResultCode export(Collection<SpanData> spans) {
                captured.addAll(spans);
                return CompletableResultCode.ofSuccess();
            }

            @Override
            public CompletableResultCode flush() {
                return CompletableResultCode.ofSuccess();
            }

            @Override
            public CompletableResultCode shutdown() {
                return CompletableResultCode.ofSuccess();
            }
        }, TracingSettingsSourceTestFixtures.boundDefaults(AgentInstanceId.random()), runtime);

        // when
        SpanData span = ExporterBoundaryTestSupport.baseSpan("op")
                .setEvents(List.of(EventData.create(
                        1L, "db.query", Attributes.of(AttributeKey.stringKey("db.password"), "supersecret"))))
                .build();
        safe.export(List.of(span));

        // then
        assertThat(captured).hasSize(1);
        assertThat(captured.getFirst().getEvents().getFirst().getAttributes().get(AttributeKey.stringKey("db.password")))
                .isNull();
        assertThat(safe.getExportedSpans()).isEqualTo(1);
    }

    @Test
    @DisplayName("Batch-accounting: a fail-closed boundary drop shrinks the effective batch without a transport failure")
    void failClosedBoundaryDropShrinksEffectiveBatchWithoutTransportFailure() {
        // given
        ScrubbingRuntime runtime = enabledRuntime("email");
        AtomicInteger delegateBatchSize = new AtomicInteger(-1);
        SafeSpanExporter safe = safeExporter(new SpanExporter() {
            @Override
            public CompletableResultCode export(Collection<SpanData> spans) {
                delegateBatchSize.set(spans.size());
                return CompletableResultCode.ofSuccess();
            }

            @Override
            public CompletableResultCode flush() {
                return CompletableResultCode.ofSuccess();
            }

            @Override
            public CompletableResultCode shutdown() {
                return CompletableResultCode.ofSuccess();
            }
        }, TracingSettingsSourceTestFixtures.boundDefaults(AgentInstanceId.random()), runtime);

        SpanData healthy = ExporterBoundaryTestSupport.baseSpan("op-1").build();
        SpanData poisoned = new DelegatingSpanData(
                ExporterBoundaryTestSupport.baseSpan("op-2").build()) {
            @Override
            public Attributes getAttributes() {
                throw new IllegalStateException("simulated corruption");
            }
        };

        // when
        CompletableResultCode result = safe.export(List.of(healthy, poisoned));

        // then
        assertThat(result.isSuccess()).as("boundary drop is not a transport failure").isTrue();
        assertThat(delegateBatchSize.get()).as("only the healthy span reaches the delegate").isEqualTo(1);
        assertThat(safe.getExportFailures()).isZero();
        assertThat(safe.getDroppedSpans()).isZero();
        assertThat(runtime.boundaryMetrics().collapseFailures()).isEqualTo(1L);
    }

    @Test
    @DisplayName("A hostile non-List batch that throws while being snapshotted fails closed: delegate is never called")
    void hostileBatchThrowingDuringBoundaryPreparationFailsClosedWithoutCallingDelegate() {
        // given
        ScrubbingRuntime runtime = enabledRuntime("email");
        AtomicInteger delegateCalls = new AtomicInteger();
        SafeSpanExporter safe = safeExporter(new SpanExporter() {
            @Override
            public CompletableResultCode export(Collection<SpanData> spans) {
                delegateCalls.incrementAndGet();
                return CompletableResultCode.ofSuccess();
            }

            @Override
            public CompletableResultCode flush() {
                return CompletableResultCode.ofSuccess();
            }

            @Override
            public CompletableResultCode shutdown() {
                return CompletableResultCode.ofSuccess();
            }
        }, TracingSettingsSourceTestFixtures.boundDefaults(AgentInstanceId.random()), runtime);

        Collection<SpanData> hostileBatch = new AbstractCollection<>() {
            @Nonnull
            @Override
            public Iterator<SpanData> iterator() {
                throw new IllegalStateException("simulated hostile boundary adapter failure");
            }

            @Override
            public int size() {
                return 1;
            }
        };

        // when
        CompletableResultCode result = safe.export(hostileBatch);

        // then
        assertThat(result.isSuccess())
                .as("boundary preparation failure must never fall through to delegate.export(rawBatch)")
                .isFalse();
        assertThat(delegateCalls.get()).as("delegate must never be called with an unscrubbed batch").isZero();
        assertThat(safe.getExportFailures()).as("boundary failure is not a transport failure").isZero();
        assertThat(safe.getDroppedSpans()).as("boundary failure is not a transport drop").isZero();
        assertThat(runtime.boundaryMetrics().batchPreparationFailures()).isEqualTo(1L);
        assertThat(runtime.boundaryMetrics().spansDroppedFailClosed()).isEqualTo(1L);
    }

    @Test
    @DisplayName("A hostile batch that throws from size() itself fails closed: delegate is never called")
    void hostileBatchThrowingFromSizeFailsClosedWithoutCallingDelegate() {
        // given
        ScrubbingRuntime runtime = enabledRuntime("email");
        AtomicInteger delegateCalls = new AtomicInteger();
        SafeSpanExporter safe = safeExporter(new SpanExporter() {
            @Override
            public CompletableResultCode export(Collection<SpanData> spans) {
                delegateCalls.incrementAndGet();
                return CompletableResultCode.ofSuccess();
            }

            @Override
            public CompletableResultCode flush() {
                return CompletableResultCode.ofSuccess();
            }

            @Override
            public CompletableResultCode shutdown() {
                return CompletableResultCode.ofSuccess();
            }
        }, TracingSettingsSourceTestFixtures.boundDefaults(AgentInstanceId.random()), runtime);

        Collection<SpanData> hostileBatch = new AbstractCollection<>() {
            @Nonnull
            @Override
            public Iterator<SpanData> iterator() {
                return Collections.emptyIterator();
            }

            @Override
            public int size() {
                throw new IllegalStateException("simulated hostile size() failure");
            }
        };

        // when
        CompletableResultCode result = safe.export(hostileBatch);

        // then
        assertThat(result.isSuccess())
                .as("a hostile size() must fail closed exactly like a hostile iterator()")
                .isFalse();
        assertThat(delegateCalls.get()).as("delegate must never be called").isZero();
        assertThat(safe.getExportFailures()).isZero();
        assertThat(safe.getDroppedSpans()).isZero();
        assertThat(runtime.boundaryMetrics().batchPreparationFailures()).isEqualTo(1L);
        assertThat(runtime.boundaryMetrics().spansDroppedFailClosed())
                .as("original count is unknown when size() itself throws; nothing countable was dropped")
                .isZero();
    }

    @Test
    @DisplayName("A boundary preparation failure never logs the raw exception message/toString()")
    void boundaryPreparationFailureNeverLogsSensitiveThrowableMessage() {
        // given
        String secret = "secret-canary-4111111111111111";
        ScrubbingRuntime runtime = enabledRuntime("email");
        SafeSpanExporter safe = safeExporter(new SpanExporter() {
            @Override
            public CompletableResultCode export(Collection<SpanData> spans) {
                throw new AssertionError("delegate must never be invoked");
            }

            @Override
            public CompletableResultCode flush() {
                return CompletableResultCode.ofSuccess();
            }

            @Override
            public CompletableResultCode shutdown() {
                return CompletableResultCode.ofSuccess();
            }
        }, TracingSettingsSourceTestFixtures.boundDefaults(AgentInstanceId.random()), runtime);

        Collection<SpanData> hostileBatch = new AbstractCollection<>() {
            @Nonnull
            @Override
            public Iterator<SpanData> iterator() {
                throw new IllegalStateException("Cannot process customer token=" + secret);
            }

            @Override
            public int size() {
                return 1;
            }
        };

        try (AgentLogCapture capture = AgentLogCapture.installed()) {
            // when
            CompletableResultCode result = safe.export(hostileBatch);

            // then
            assertThat(result.isSuccess()).isFalse();
            assertThat(capture.events()).isNotEmpty();
            assertThat(capture.events()).allSatisfy(event -> {
                assertThat(event.fields().toString())
                        .doesNotContain(secret, "Cannot process customer token");
                if (event.throwableDigest() != null) {
                    assertThat(event.throwableDigest())
                            .doesNotContain(secret, "Cannot process customer token");
                }
            });
        }
    }

    @Test
    @DisplayName("A hostile batch whose second size() call (post-boundary-scrubbing) throws fails closed")
    void hostileBatchWhoseSecondSizeCallThrowsFailsClosed() {
        // given
        ScrubbingRuntime runtime = enabledRuntime("email");
        AtomicInteger delegateCalls = new AtomicInteger();
        SafeSpanExporter safe = safeExporter(new SpanExporter() {
            @Override
            public CompletableResultCode export(Collection<SpanData> spans) {
                delegateCalls.incrementAndGet();
                return CompletableResultCode.ofSuccess();
            }

            @Override
            public CompletableResultCode flush() {
                return CompletableResultCode.ofSuccess();
            }

            @Override
            public CompletableResultCode shutdown() {
                return CompletableResultCode.ofSuccess();
            }
        }, TracingSettingsSourceTestFixtures.boundDefaults(AgentInstanceId.random()), runtime);

        SpanData span = ExporterBoundaryTestSupport.baseSpan("op").build();
        AtomicInteger sizeCalls = new AtomicInteger();
        List<SpanData> hostileBatch = new AbstractList<>() {
            @Override
            public SpanData get(int index) {
                return span;
            }

            @Nonnull
            @Override
            public Iterator<SpanData> iterator() {
                return List.of(span).iterator();
            }

            @Override
            public int size() {
                if (sizeCalls.incrementAndGet() <= 2) {
                    return 1;
                }

                throw new IllegalStateException("simulated hostile size() failure on the 3rd call");
            }
        };

        // when
        CompletableResultCode result = safe.export(hostileBatch);

        // then
        assertThat(sizeCalls.get())
                .as("the fixture must actually reach the post-boundary-scrubbing size() call")
                .isGreaterThanOrEqualTo(3);
        assertThat(result.isSuccess())
                .as("a hostile size() call after boundary scrubbing must fail closed exactly like the first")
                .isFalse();
        assertThat(delegateCalls.get()).as("delegate must never be called").isZero();
        assertThat(safe.getExportFailures()).isZero();
        assertThat(safe.getDroppedSpans()).isZero();
        assertThat(runtime.boundaryMetrics().batchPreparationFailures()).isEqualTo(1L);
    }

    @Test
    @DisplayName("Batch-accounting: when every span is boundary-dropped, the delegate is never invoked (success)")
    void wholeBatchBoundaryDroppedNeverCallsDelegate() {
        // given
        ScrubbingRuntime runtime = enabledRuntime("email");
        AtomicInteger delegateCalls = new AtomicInteger();
        SafeSpanExporter safe = safeExporter(new SpanExporter() {
            @Override
            public CompletableResultCode export(Collection<SpanData> spans) {
                delegateCalls.incrementAndGet();
                return CompletableResultCode.ofSuccess();
            }

            @Override
            public CompletableResultCode flush() {
                return CompletableResultCode.ofSuccess();
            }

            @Override
            public CompletableResultCode shutdown() {
                return CompletableResultCode.ofSuccess();
            }
        }, TracingSettingsSourceTestFixtures.boundDefaults(AgentInstanceId.random()), runtime);

        SpanData poisoned = new DelegatingSpanData(
                ExporterBoundaryTestSupport.baseSpan("op").build()
        ) {
            @Override
            public Attributes getAttributes() {
                throw new IllegalStateException("simulated corruption");
            }
        };

        // when
        CompletableResultCode result = safe.export(List.of(poisoned));

        // then
        assertThat(result.isSuccess()).isTrue();
        assertThat(delegateCalls.get()).as("empty effective batch never reaches the delegate").isZero();
        assertThat(safe.getExportBatches()).isZero();
    }

    @Test
    @DisplayName("metricsSnapshot exposes boundary counters only when scrubbing is enabled")
    void metricsSnapshotExposesBoundaryCountersOnlyWhenEnabled() {
        // given
        ScrubbingRuntime disabled = ScrubbingRuntimeTestFixtures.disabled();
        try (SafeSpanExporter withDisabledRuntime = new SafeSpanExporter(
                delegate(CompletableResultCode.ofSuccess()), TracingSettingsSourceTestFixtures.boundDefaults(AgentInstanceId.random()), disabled)) {
            // when/then
            assertThat(withDisabledRuntime.metricsSnapshot()).doesNotContainKey("boundary.input_spans");
        }

        // given
        ScrubbingRuntime enabled = enabledRuntime("email");
        enabled.recordBoundaryValueAttributesRemoved(2L);
        try (SafeSpanExporter withEnabledRuntime = new SafeSpanExporter(
                delegate(CompletableResultCode.ofSuccess()), TracingSettingsSourceTestFixtures.boundDefaults(AgentInstanceId.random()), enabled)) {
            // when
            withEnabledRuntime.export(List.of(ExporterBoundaryTestSupport.baseSpan("op").build()));

            // then
            assertThat(withEnabledRuntime.metricsSnapshot())
                    .containsEntry("boundary.value_attributes_removed", 2L)
                    .containsKey("boundary.input_spans");
        }
    }

    @Test
    @DisplayName("Only the production constructor is public")
    void onlyProductionConstructorIsPublic() {
        // when
        Constructor<?>[] constructors = SafeSpanExporter.class.getDeclaredConstructors();

        // then
        for (Constructor<?> constructor : constructors) {
            boolean productionSignature = List.of(constructor.getParameterTypes()).equals(List.of(
                    SpanExporter.class,
                    TracingSettingsSource.class,
                    ScrubbingRuntime.class
            ));
            assertThat(Modifier.isPublic(constructor.getModifiers()))
                    .as("only the production constructor may be public: %s", constructor)
                    .isEqualTo(productionSignature);
        }
    }

    private record ExportStep(CompletableResultCode result,
                              Consumer<ExportResponseObservation> response) {

        private ExportStep {
            Objects.requireNonNull(response, "response");
        }
    }

    private static final class QueuedSpanExporter implements SpanExporter {

        private final Deque<ExportStep> steps = new ArrayDeque<>();

        private QueuedSpanExporter(ExportStep... steps) {
            Collections.addAll(this.steps, steps);
        }

        @Override
        public synchronized CompletableResultCode export(Collection<SpanData> spans) {
            ExportStep step = steps.removeFirst();
            ExportResponseObservation observation = Objects.requireNonNull(
                    ExportResponseScope.current(),
                    "export operation must be observable"
            );
            step.response().accept(observation);
            return step.result();
        }

        @Override
        public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }
    }

    private static final class SelectiveFailureOutputStream extends OutputStream {

        private final ByteArrayOutputStream delivered = new ByteArrayOutputStream();
        private final String rejectedFragment;
        private final boolean fatal;
        private int failures;

        private SelectiveFailureOutputStream(String rejectedFragment) {
            this(rejectedFragment, false);
        }

        private SelectiveFailureOutputStream(String rejectedFragment, boolean fatal) {
            this.rejectedFragment = Objects.requireNonNull(rejectedFragment, "rejectedFragment");
            this.fatal = fatal;
        }

        @Override
        public synchronized void write(int value) {
            delivered.write(value);
        }

        @Override
        public synchronized void write(@Nonnull byte[] bytes, int offset, int length) throws IOException {
            String payload = new String(bytes, offset, length, StandardCharsets.UTF_8);
            if (payload.contains(rejectedFragment)) {
                failures++;
                if (fatal) {
                    throw new LinkageError("simulated fatal log delivery failure");
                }

                throw new IOException("simulated log delivery failure");
            }

            delivered.write(bytes, offset, length);
        }

        private synchronized int failures() {
            return failures;
        }

        private synchronized String contents() {
            return delivered.toString(StandardCharsets.UTF_8);
        }
    }

    private record LifecycleSpanExporter(CompletableResultCode flushResult,
                                         CompletableResultCode shutdownResult) implements SpanExporter {

        @Override
        public CompletableResultCode export(Collection<SpanData> spans) {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode flush() {
            return flushResult;
        }

        @Override
        public CompletableResultCode shutdown() {
            return shutdownResult;
        }
    }

    @Test
    @DisplayName("Clean batch is forwarded as the same Collection instance (no defensive copy)")
    void cleanBatchIsForwardedWithoutCopying() {
        // given
        ScrubbingRuntime enabled = enabledRuntime("email");
        List<Collection<SpanData>> seen = new ArrayList<>();
        SafeSpanExporter safe = safeExporter(new SpanExporter() {
            @Override
            public CompletableResultCode export(Collection<SpanData> spans) {
                seen.add(spans);
                return CompletableResultCode.ofSuccess();
            }

            @Override
            public CompletableResultCode flush() {
                return CompletableResultCode.ofSuccess();
            }

            @Override
            public CompletableResultCode shutdown() {
                return CompletableResultCode.ofSuccess();
            }
        }, TracingSettingsSourceTestFixtures.boundDefaults(AgentInstanceId.random()), enabled);

        Collection<SpanData> batch = List.of(ExporterBoundaryTestSupport.baseSpan("clean").build());

        // when
        safe.export(batch);

        // then
        assertThat(seen).hasSize(1);
        assertThat(seen.getFirst()).isSameAs(batch);
    }
}
