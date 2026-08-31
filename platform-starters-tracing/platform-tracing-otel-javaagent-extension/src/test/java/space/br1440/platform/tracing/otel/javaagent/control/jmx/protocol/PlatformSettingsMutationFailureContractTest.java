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
import java.util.UUID;

import javax.management.MBeanException;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.CompositeDataSupport;
import javax.management.openmbean.CompositeType;
import javax.management.openmbean.OpenType;
import javax.management.openmbean.SimpleType;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import space.br1440.platform.tracing.otel.javaagent.control.settings.TracingSettingsStore;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.SettingsSourceAuthority;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.SettingsUpdateTrigger;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.SettingsValidationConstraints;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.TracingSettings;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.LastSettingsUpdate;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.SettingsRejectionReason;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.SettingsUpdateAttempt;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.SettingsUpdateResult;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.SettingsUpdateStatus;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.TracingSettingsUpdater;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.JmxWireContract;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentEventCodes;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLogCapture;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLogEvent;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLogLevel;

final class PlatformSettingsMutationFailureContractTest {

    @RegisterExtension
    final AgentLogCapture capture = new AgentLogCapture();

    @Test
    void unexpectedDecodeFailureIsRecordedWithoutLoggingUntrustedAttemptId() throws Exception {
        // given
        String maliciousAttemptId = "invalid\r\nforged-log-line-" + "x".repeat(1024);
        CompositeData request = wireRequest(maliciousAttemptId);
        TracingSettingsStore store = JmxSettingsMutationFixtures.store();
        LastSettingsUpdate diagnostics = new LastSettingsUpdate();
        TracingSettingsUpdater updater = spy(updater(store, diagnostics));
        JmxControlOpenTypeCodec codec = mock(JmxControlOpenTypeCodec.class);
        RuntimeException decodeFailure = new IllegalStateException("decode failed");
        CompositeData encodedFailure = mock(CompositeData.class);
        when(codec.decode(request)).thenThrow(decodeFailure);
        when(codec.encode(any(SettingsUpdateResult.class), eq("")))
                .thenReturn(encodedFailure);
        PlatformSettingsMutationMBean writer = new PlatformSettingsMutationMBean(updater, codec);

        // when
        Object response = invoke(writer, request);

        // then
        assertThat(response).isSameAs(encodedFailure);
        assertThat(diagnostics.snapshot().latestOutcome()).isEqualTo(SettingsUpdateStatus.FAILED);
        assertThat(diagnostics.snapshot().latestMessageCode())
                .isEqualTo(SettingsRejectionReason.INTERNAL_FAILURE);
        assertThat(diagnostics.snapshot().latestAttempt()).isNull();
        verify(updater).failBeforeApply(
                "",
                SettingsRejectionReason.INTERNAL_FAILURE);
        assertThat(warnEvents()).singleElement().satisfies(event -> {
            assertThat(event.eventCode()).isEqualTo(AgentEventCodes.CTRL_WRITER);
            assertThat(event.fields()).containsEntry("attemptId", "<unavailable>");
            assertThat(event.fields().toString()).doesNotContain(maliciousAttemptId, "forged-log-line");
            assertThat(event.throwableDigest()).isNotNull();
        });
        assertThat(capture.rendered()).doesNotContain(maliciousAttemptId, "forged-log-line");
    }

    @Test
    void applyFailureDoesNotExposeForeignThrowable() throws Exception {
        // given
        TracingSettingsStore store = JmxSettingsMutationFixtures.store();
        TracingSettingsUpdater updater = mock(TracingSettingsUpdater.class);
        JmxControlOpenTypeCodec codec = mock(JmxControlOpenTypeCodec.class);
        CompositeData request = wireRequest(UUID.randomUUID().toString());
        SettingsUpdateAttempt attempt = attempt();
        DecodedSettingsUpdate decoded = new DecodedSettingsUpdate(
                attempt,
                store.current().base(),
                JmxSettingsMutationFixtures.settings(0.25d));
        RuntimeException applyFailure = new ForeignProviderFailure("foreign apply failure");
        when(codec.decode(request)).thenReturn(decoded);
        when(updater.apply(decoded.attempt(), decoded.expectedBase(), decoded.settings()))
                .thenThrow(applyFailure);
        PlatformSettingsMutationMBean writer = new PlatformSettingsMutationMBean(updater, codec);

        // when
        MBeanException thrown = catchThrowableOfType(
                MBeanException.class,
                () -> invoke(writer, request));

        // then
        assertNeutralBoundary(
                thrown,
                applyFailure,
                "JMX_CONTROL_APPLY_FAILED: Settings snapshot update failed");
        assertThat(warnEvents()).singleElement().satisfies(event -> {
            assertThat(event.eventCode()).isEqualTo(AgentEventCodes.CTRL_WRITER);
            assertThat(event.fields()).containsEntry("attemptId", attempt.attemptId());
            assertThat(event.throwableDigest()).isNotNull();
        });
    }

    @Test
    void responseEncodingFailurePreservesAppliedStateAndNeutralizesThrowable() throws Exception {
        // given
        TracingSettingsStore store = JmxSettingsMutationFixtures.store();
        LastSettingsUpdate diagnostics = new LastSettingsUpdate();
        TracingSettingsUpdater updater = spy(updater(store, diagnostics));
        JmxControlOpenTypeCodec codec = mock(JmxControlOpenTypeCodec.class);
        CompositeData request = wireRequest(UUID.randomUUID().toString());
        SettingsUpdateAttempt attempt = attempt();
        TracingSettings candidate = JmxSettingsMutationFixtures.settings(0.25d);
        DecodedSettingsUpdate decoded = new DecodedSettingsUpdate(
                attempt,
                store.current().base(),
                candidate);
        RuntimeException encodeFailure = new IllegalStateException("encode failed");
        when(codec.decode(request)).thenReturn(decoded);
        when(codec.encode(any(SettingsUpdateResult.class), eq(attempt.attemptId())))
                .thenThrow(encodeFailure);
        PlatformSettingsMutationMBean writer = new PlatformSettingsMutationMBean(updater, codec);

        // when
        MBeanException thrown = catchThrowableOfType(
                MBeanException.class,
                () -> invoke(writer, request));

        // then
        assertNeutralBoundary(
                thrown,
                encodeFailure,
                "JMX_CONTROL_RESPONSE_ENCODING_FAILED: Settings snapshot response encoding failed");
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
    void preApplyResponseEncodingFailureUsesNeutralFixedCode() throws Exception {
        // given
        String wireAttemptId = "not-a-canonical-id";
        CompositeData request = wireRequest(wireAttemptId);
        TracingSettingsStore store = JmxSettingsMutationFixtures.store();
        LastSettingsUpdate diagnostics = new LastSettingsUpdate();
        TracingSettingsUpdater updater = updater(store, diagnostics);
        JmxControlOpenTypeCodec codec = mock(JmxControlOpenTypeCodec.class);
        RuntimeException decodeFailure = new IllegalStateException("decode failed");
        RuntimeException encodeFailure = new IllegalArgumentException("failure response encode failed");
        when(codec.decode(request)).thenThrow(decodeFailure);
        when(codec.encode(any(SettingsUpdateResult.class), eq("")))
                .thenThrow(encodeFailure);
        PlatformSettingsMutationMBean writer = new PlatformSettingsMutationMBean(updater, codec);

        // when
        MBeanException thrown = catchThrowableOfType(
                MBeanException.class,
                () -> invoke(writer, request));

        // then
        assertNeutralBoundary(
                thrown,
                encodeFailure,
                "JMX_CONTROL_PRE_APPLY_ENCODING_FAILED: Pre-apply settings response encoding failed");
        assertThat(diagnostics.snapshot().latestOutcome()).isEqualTo(SettingsUpdateStatus.FAILED);
        assertThat(warnEvents()).hasSize(2)
                .allSatisfy(event -> assertThat(event.fields())
                        .containsEntry("attemptId", "<unavailable>"));
    }

    private static Object invoke(
            PlatformSettingsMutationMBean writer,
            CompositeData request
    ) throws Exception {
        return writer.invoke(
                JmxWireContract.OPERATION_SETTINGS_MUTATION_APPLY_SNAPSHOT,
                new Object[]{request},
                new String[]{CompositeData.class.getName()});
    }

    private List<AgentLogEvent> warnEvents() {
        return capture.events().stream()
                .filter(event -> event.level() == AgentLogLevel.WARN)
                .toList();
    }

    private static void assertNeutralBoundary(
            MBeanException thrown,
            RuntimeException foreignFailure,
            String expectedMessage
    ) {
        assertThat(thrown.getTargetException()).isNull();
        assertThat(thrown.getCause()).isNull();
        assertThat(thrown.getSuppressed()).isEmpty();
        assertThat(thrown.getMessage())
                .isEqualTo(expectedMessage)
                .doesNotContain(
                        foreignFailure.getClass().getName(),
                        foreignFailure.getMessage());
    }

    private static CompositeData wireRequest(String attemptId) throws Exception {
        CompositeType type = new CompositeType(
                "TestApplySnapshotRequest",
                "Test applySnapshot request",
                new String[]{JmxWireContract.FIELD_APPLY_SNAPSHOT_REQUEST_ATTEMPT_ID},
                new String[]{JmxWireContract.FIELD_APPLY_SNAPSHOT_REQUEST_ATTEMPT_ID},
                new OpenType<?>[]{SimpleType.STRING});
        return new CompositeDataSupport(
                type,
                new String[]{JmxWireContract.FIELD_APPLY_SNAPSHOT_REQUEST_ATTEMPT_ID},
                new Object[]{attemptId});
    }

    private static TracingSettingsUpdater updater(
            TracingSettingsStore store,
            LastSettingsUpdate diagnostics
    ) {
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

    private static final class ForeignProviderFailure extends RuntimeException {

        private ForeignProviderFailure(String message) {
            super(message);
        }
    }
}
