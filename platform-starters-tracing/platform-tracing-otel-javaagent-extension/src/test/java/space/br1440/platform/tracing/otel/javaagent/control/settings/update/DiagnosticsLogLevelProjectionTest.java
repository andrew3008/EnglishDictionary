package space.br1440.platform.tracing.otel.javaagent.control.settings.update;

import static org.assertj.core.api.Assertions.assertThat;
import static space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSourceTestFixtures.boundDefaults;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsRevision;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.SettingsWriter;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.TracingSettings;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogLevel;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLogCapture;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLoggingRuntime;

@ResourceLock(AgentLogCapture.RESOURCE_LOCK)
class DiagnosticsLogLevelProjectionTest {

    @Test
    void applied_diagnostics_level_matches_effective_level() {
        // given
        TracingSettingsSource source = boundDefaults(AgentInstanceId.random());
        AgentLoggingRuntime.shared().applyLevel(AgentLogLevel.INFO);
        TracingSettings candidate = withLevel(source.current().policy(), AgentLogLevel.DEBUG);

        // when
        SettingsUpdateResult result = source.updater().apply(
                attempt(),
                source.current().base(),
                candidate);

        // then
        assertThat(result.outcome()).isEqualTo(SettingsUpdateStatus.APPLIED);
        assertThat(AgentLoggingRuntime.shared().effectiveLevel())
                .isEqualTo(source.current().policy().diagnosticsLogLevel())
                .isEqualTo(AgentLogLevel.DEBUG);
    }

    @Test
    void concurrent_applies_keep_effective_level_equal_to_the_store() throws Exception {
        // given
        TracingSettingsSource source = boundDefaults(AgentInstanceId.random());
        AgentLogLevel[] levels = {AgentLogLevel.DEBUG, AgentLogLevel.WARN};

        // when
        for (int iteration = 0; iteration < 1000; iteration++) {
            TracingSettingsRevision witnessed = source.current();
            CountDownLatch start = new CountDownLatch(1);
            AtomicReference<Throwable> leftFailure = new AtomicReference<>();
            AtomicReference<Throwable> rightFailure = new AtomicReference<>();
            Thread left = applyThread(source, witnessed, levels[0], start, leftFailure);
            Thread right = applyThread(source, witnessed, levels[1], start, rightFailure);
            left.start();
            right.start();
            start.countDown();
            left.join();
            right.join();

            // then
            assertThat(leftFailure.get()).isNull();
            assertThat(rightFailure.get()).isNull();
            assertThat(AgentLoggingRuntime.shared().effectiveLevel())
                    .isEqualTo(source.current().policy().diagnosticsLogLevel());
        }
    }

    private static Thread applyThread(TracingSettingsSource source,
                                      TracingSettingsRevision witnessed,
                                      AgentLogLevel level,
                                      CountDownLatch start,
                                      AtomicReference<Throwable> failure) {
        Thread thread = new Thread(() -> {
            try {
                start.await();
                source.updater().apply(
                        attempt(),
                        witnessed.base(),
                        withLevel(witnessed.policy(), level)
                );
            } catch (Throwable thrown) {
                failure.set(thrown);
            }
        });
        thread.setDaemon(true);
        return thread;
    }

    private static TracingSettings withLevel(TracingSettings current, AgentLogLevel level) {
        return TracingSettings.of(
                current.samplingEnabled(),
                current.samplingRatio(),
                current.samplingRouteRatios(),
                current.validationLevel(),
                current.exporterEnabled(),
                current.traceControlHeadersEnabled(),
                level);
    }

    private static SettingsUpdateAttempt attempt() {
        return new SettingsUpdateAttempt(
                UUID.randomUUID().toString(),
                SettingsWriter.CONFIG_SERVER);
    }
}
