package space.br1440.platform.tracing.otel.javaagent.control.jmx.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.management.MBeanException;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.CompositeDataSupport;
import javax.management.openmbean.CompositeType;
import javax.management.openmbean.OpenType;
import javax.management.openmbean.SimpleType;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import space.br1440.platform.tracing.otel.javaagent.logging.AgentLogEvent;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLogLevel;
import space.br1440.platform.tracing.otel.javaagent.control.settings.TracingSettingsStore;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.SettingsSourceAuthority;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.SettingsUpdateTrigger;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.SettingsValidationConstraints;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.TracingLogLevel;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.TracingSettings;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.LastSettingsUpdate;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.SettingsRejectionReason;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.SettingsUpdateAttempt;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.SettingsUpdateResult;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.SettingsUpdateStatus;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.TracingSettingsUpdater;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentEventCodes;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLogCapture;

class PrivateControlWriterFailureContractTest {

    @RegisterExtension
    AgentLogCapture capture = new AgentLogCapture();

    @Test
    void unexpectedDecodeFailureIsRecordedWithoutLoggingUntrustedAttemptId() throws Exception {
        String maliciousAttemptId = "invalid\r\nforged-log-line-" + "x".repeat(1024);
        CompositeData request = request(maliciousAttemptId);
        TracingSettingsStore store = store();
        LastSettingsUpdate diagnostics = new LastSettingsUpdate();
        TracingSettingsUpdater updater = spy(updater(store, diagnostics));
        PrivateControlOpenTypeCodec codec = mock(PrivateControlOpenTypeCodec.class);
        RuntimeException decodeFailure = new IllegalStateException("decode failed");
        CompositeData encodedFailure = mock(CompositeData.class);
        when(codec.decode(request)).thenThrow(decodeFailure);
        when(codec.encode(any(SettingsUpdateResult.class), eq(maliciousAttemptId)))
                .thenReturn(encodedFailure);
        PrivateControlWriterMBean writer = new PrivateControlWriterMBean(updater, store, codec);

        Object response = invoke(writer, request);

        assertThat(response).isSameAs(encodedFailure);
        assertThat(diagnostics.snapshot().latestOutcome()).isEqualTo(SettingsUpdateStatus.FAILED);
        assertThat(diagnostics.snapshot().latestMessageCode())
                .isEqualTo(SettingsRejectionReason.INTERNAL_FAILURE);
        assertThat(diagnostics.snapshot().latestAttempt()).isNull();
        verify(updater).failBeforeApply(
                maliciousAttemptId,
                SettingsRejectionReason.INTERNAL_FAILURE);
        assertThat(warnEvents()).singleElement().satisfies(event -> {
            assertThat(event.eventCode()).isEqualTo(AgentEventCodes.CTRL_WRITER);
            assertThat(event.fields()).containsEntry("attemptId", "<unavailable>");
            assertThat(event.fields().toString()).doesNotContain(maliciousAttemptId, "forged-log-line");
            assertThat(capture.rendered()).doesNotContain(maliciousAttemptId, "forged-log-line");
            assertThat(event.throwableDigest()).isNotNull();
        });
    }

    @Test
    void encodeFailureAfterAppliedPreservesAcceptedOutcome() throws Exception {
        TracingSettingsStore store = store();
        LastSettingsUpdate diagnostics = new LastSettingsUpdate();
        TracingSettingsUpdater updater = spy(updater(store, diagnostics));
        PrivateControlOpenTypeCodec codec = mock(PrivateControlOpenTypeCodec.class);
        CompositeData request = request(UUID.randomUUID().toString());
        SettingsUpdateAttempt attempt = attempt();
        TracingSettings candidate = settings(0.25d);
        DecodedSettingsUpdate decoded = new DecodedSettingsUpdate(
                attempt,
                store.current().base(),
                candidate);
        RuntimeException encodeFailure = new IllegalStateException("encode failed");
        when(codec.decode(request)).thenReturn(decoded);
        when(codec.encode(any(SettingsUpdateResult.class), eq(attempt.attemptId())))
                .thenThrow(encodeFailure);
        PrivateControlWriterMBean writer = new PrivateControlWriterMBean(updater, store, codec);

        MBeanException thrown = catchThrowableOfType(
                () -> invoke(writer, request),
                MBeanException.class);

        assertThat(thrown.getTargetException()).isSameAs(encodeFailure);
        assertThat(store.current().acceptedSequence()).isEqualTo(1L);
        assertThat(store.current().policy()).isEqualTo(candidate);
        assertThat(diagnostics.snapshot().latestOutcome()).isEqualTo(SettingsUpdateStatus.APPLIED);
        assertThat(diagnostics.snapshot().countOf(SettingsUpdateStatus.APPLIED)).isEqualTo(1L);
        assertThat(diagnostics.snapshot().countOf(SettingsUpdateStatus.FAILED)).isZero();
        verify(updater, never()).failBeforeApply(any(), any());
        assertThat(warnEvents()).singleElement().satisfies(event -> {
            assertThat(event.eventCode()).isEqualTo(AgentEventCodes.CTRL_WRITER);
            assertThat(event.fields()).containsEntry("attemptId", attempt.attemptId());
            assertThat(event.throwableDigest()).isNotNull();
        });
    }

    @Test
    void failureResponseEncodeFailureEscapesAsMBeanException() throws Exception {
        String wireAttemptId = "not-a-canonical-id";
        CompositeData request = request(wireAttemptId);
        TracingSettingsStore store = store();
        LastSettingsUpdate diagnostics = new LastSettingsUpdate();
        TracingSettingsUpdater updater = updater(store, diagnostics);
        PrivateControlOpenTypeCodec codec = mock(PrivateControlOpenTypeCodec.class);
        RuntimeException decodeFailure = new IllegalStateException("decode failed");
        RuntimeException encodeFailure = new IllegalArgumentException("failure response encode failed");
        when(codec.decode(request)).thenThrow(decodeFailure);
        when(codec.encode(any(SettingsUpdateResult.class), eq(wireAttemptId)))
                .thenThrow(encodeFailure);
        PrivateControlWriterMBean writer = new PrivateControlWriterMBean(updater, store, codec);

        MBeanException thrown = catchThrowableOfType(
                () -> invoke(writer, request),
                MBeanException.class);

        assertThat(thrown.getTargetException()).isSameAs(encodeFailure);
        assertThat(diagnostics.snapshot().latestOutcome()).isEqualTo(SettingsUpdateStatus.FAILED);
        assertThat(warnEvents()).hasSize(2);
        assertThat(warnEvents().get(1).fields()).containsEntry("attemptId", "<unavailable>");
    }

    private static Object invoke(PrivateControlWriterMBean writer, CompositeData request)
            throws Exception {
        return writer.invoke(
                "applySnapshot",
                new Object[]{request},
                new String[]{CompositeData.class.getName()});
    }

    private List<AgentLogEvent> warnEvents() {
        return capture.events().stream()
                .filter(event -> event.level() == AgentLogLevel.WARN)
                .toList();
    }

    private static CompositeData request(String attemptId) throws Exception {
        CompositeType type = new CompositeType(
                "TestApplySnapshotRequest",
                "Test apply snapshot request",
                new String[]{"attemptId"},
                new String[]{"attemptId"},
                new OpenType<?>[]{SimpleType.STRING});
        return new CompositeDataSupport(
                type,
                new String[]{"attemptId"},
                new Object[]{attemptId});
    }

    private static TracingSettingsStore store() {
        return new TracingSettingsStore(UUID.randomUUID().toString(), settings(1.0d));
    }

    private static TracingSettingsUpdater updater(
            TracingSettingsStore store,
            LastSettingsUpdate diagnostics) {
        return new TracingSettingsUpdater(
                store,
                new SettingsValidationConstraints(true),
                diagnostics);
    }

    private static SettingsUpdateAttempt attempt() {
        return new SettingsUpdateAttempt(
                UUID.randomUUID().toString(),
                Instant.now(),
                SettingsSourceAuthority.CONFIG_SERVER,
                SettingsUpdateTrigger.REFRESH,
                "revision-1");
    }

    private static TracingSettings settings(double ratio) {
        return TracingSettings.of(
                true,
                ratio,
                Map.of(),
                true,
                false,
                true,
                true,
                TracingLogLevel.INFO);
    }
}
