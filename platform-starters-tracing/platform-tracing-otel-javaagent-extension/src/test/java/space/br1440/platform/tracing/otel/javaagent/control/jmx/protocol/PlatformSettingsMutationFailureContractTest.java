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
import org.junit.jupiter.api.parallel.ResourceLock;

import space.br1440.platform.tracing.otel.javaagent.control.settings.TracingSettingsStore;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.SettingsWriter;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.TracingSettings;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.SettingsRejectionReason;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.SettingsUpdateAttempt;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.SettingsUpdateResult;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.TracingSettingsUpdater;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.JmxWireContract;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLogCapture;
import space.br1440.platform.tracing.otel.javaagent.logging.CapturedLogEvent;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentEventCode;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogLevel;

@ResourceLock(AgentLogCapture.RESOURCE_LOCK)
final class PlatformSettingsMutationFailureContractTest {

    @RegisterExtension
    final AgentLogCapture capture = new AgentLogCapture();

    @Test
    void unexpectedDecodeFailureIsRecordedWithoutLoggingUntrustedAttemptId() throws Exception {
        // given
        String maliciousAttemptId = "invalid\r\nforged-log-line-" + "x".repeat(1024);
        CompositeData request = wireRequest(maliciousAttemptId);
        TracingSettingsStore store = JmxSettingsMutationFixtures.store();
        TracingSettingsUpdater updater = spy(updater(store));
        JmxControlOpenTypeCodec codec = mock(JmxControlOpenTypeCodec.class);
        RuntimeException decodeFailure = new IllegalStateException("decode failed");
        CompositeData encodedFailure = mock(CompositeData.class);
        when(codec.decode(request)).thenThrow(decodeFailure);
        when(codec.encode(any(SettingsUpdateResult.class), eq(""))).thenReturn(encodedFailure);
        PlatformSettingsMutationMBean writer = new PlatformSettingsMutationMBean(updater, codec);

        // when
        Object response = invoke(writer, request);

        // then
        assertThat(response).isSameAs(encodedFailure);
        assertThat(store.current().acceptedSequence()).isZero();
        verify(updater).failBeforeApply(SettingsRejectionReason.INTERNAL_FAILURE);
        assertThat(warnEvents()).singleElement().satisfies(event -> {
            assertThat(event.eventCode()).isEqualTo(AgentEventCode.CTRL_WRITER);
            assertThat(event.fields()).containsEntry("attemptId", "<unavailable>");
            assertThat(event.fields().toString()).doesNotContain(maliciousAttemptId, "forged-log-line");
            assertThat(event.throwableDigest()).isNotNull();
        });
        assertThat(capture.rendered()).doesNotContain(maliciousAttemptId, "forged-log-line");
    }

    @Test
    void policyRejectionDoesNotEmitGenericMutationFailureEvent() throws Exception {
        // given
        String attemptId = UUID.randomUUID().toString();
        CompositeData request = wireRequest(attemptId);
        TracingSettingsStore store = JmxSettingsMutationFixtures.store();
        TracingSettingsUpdater updater = updater(store);
        JmxControlOpenTypeCodec codec = mock(JmxControlOpenTypeCodec.class);
        CompositeData encodedRejection = mock(CompositeData.class);
        JmxControlDecodeException rejection = new JmxControlDecodeException(
                SettingsRejectionReason.INVALID_VALIDATION_LEVEL,
                JmxWireContract.FIELD_RUNTIME_POLICY_VALIDATION_LEVEL,
                "Validation level is invalid"
        );
        when(codec.decode(request)).thenThrow(rejection);
        when(codec.encode(any(SettingsUpdateResult.class), eq(attemptId))).thenReturn(encodedRejection);
        PlatformSettingsMutationMBean writer = new PlatformSettingsMutationMBean(updater, codec);

        // when
        Object response = invoke(writer, request);

        // then
        assertThat(response).isSameAs(encodedRejection);
        assertThat(store.current().acceptedSequence()).isZero();
        assertThat(capture.events())
                .extracting(CapturedLogEvent::eventName)
                .contains("JMX_SETTINGS_POLICY_REJECTED")
                .doesNotContain("JMX_SETTINGS_MUTATION_FAILED");
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
            assertThat(event.eventCode()).isEqualTo(AgentEventCode.CTRL_WRITER);
            assertThat(event.fields()).containsEntry("attemptId", attempt.attemptId());
            assertThat(event.throwableDigest()).isNotNull();
        });
    }

    @Test
    void responseEncodingFailurePreservesAppliedStateAndNeutralizesThrowable() throws Exception {
        // given
        TracingSettingsStore store = JmxSettingsMutationFixtures.store();
        TracingSettingsUpdater updater = spy(updater(store));
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
        verify(updater, never()).failBeforeApply(any());
        assertThat(warnEvents()).singleElement().satisfies(event -> {
            assertThat(event.eventCode()).isEqualTo(AgentEventCode.CTRL_WRITER);
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
        TracingSettingsUpdater updater = updater(store);
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
        assertThat(store.current().acceptedSequence()).isZero();
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

    private List<CapturedLogEvent> warnEvents() {
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

    private static TracingSettingsUpdater updater(TracingSettingsStore store) {
        return new TracingSettingsUpdater(store, level -> { });
    }

    private static SettingsUpdateAttempt attempt() {
        return new SettingsUpdateAttempt(
                UUID.randomUUID().toString(),
                SettingsWriter.CONFIG_SERVER);
    }

    private static final class ForeignProviderFailure extends RuntimeException {

        private ForeignProviderFailure(String message) {
            super(message);
        }
    }
}
