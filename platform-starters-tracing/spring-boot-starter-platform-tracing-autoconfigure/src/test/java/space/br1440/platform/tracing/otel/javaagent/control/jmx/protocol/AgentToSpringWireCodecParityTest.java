package space.br1440.platform.tracing.otel.javaagent.control.jmx.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import javax.management.openmbean.CompositeData;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import space.br1440.platform.tracing.autoconfigure.control.jmx.protocol.AcceptedPolicySnapshotDecoder;
import space.br1440.platform.tracing.autoconfigure.control.settings.AcceptedSettings;
import space.br1440.platform.tracing.autoconfigure.control.settings.CompleteTracingSettings;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport.JmxWireGraphValidator;
import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxWireContract;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsRevision;
import space.br1440.platform.tracing.otel.javaagent.control.jmx.lifecycle.PlatformSettingsStatusMBean;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.SettingsSourceAuthority;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.TracingSettings;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.TracingSettingsUpdater;
import space.br1440.platform.tracing.otel.javaagent.control.settings.validation.SpanAttributeValidationLevel;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogLevel;

class AgentToSpringWireCodecParityTest {

    @Test
    void mixedPolicyRoundTrips() {
        assertRoundTrip(envelope(42L, uuid(), TracingSettings.of(
                        true, 0.25, Map.of("/checkout", 0.5, "/search", 0.3, "/health", 0.0),
                        SpanAttributeValidationLevel.ERROR, false, false, AgentLogLevel.DEBUG)
                )
        );
    }

    @Test
    void emptyRouteRatiosRoundTrip() {
        assertRoundTrip(envelope(7L, uuid(), TracingSettings.of(
                false, 0.0, Map.of(), SpanAttributeValidationLevel.DISABLED, false, false, AgentLogLevel.OFF)));
    }

    @Test
    void maximumBootstrapSnapshotPassesSpringWireBoundary() {
        // given
        TracingSettings policy = TracingSettings.of(
                true,
                1.0d,
                maximumRouteRatios(),
                SpanAttributeValidationLevel.DISABLED,
                true,
                true,
                AgentLogLevel.TRACE
        );
        TracingSettingsRevision envelope = envelope(
                0L,
                uuid(),
                policy,
                SettingsSourceAuthority.BOOTSTRAP
        );

        // when
        AcceptedSettings decoded = roundTrip(envelope);

        // then
        assertThat(decoded.authority().name()).isEqualTo(SettingsSourceAuthority.BOOTSTRAP.name());
        assertThat(decoded.policy().samplingRouteRatios()).isEqualTo(maximumRouteRatios());
    }

    @Test
    void allDiagnosticsLogLevelsRoundTrip() {
        for (AgentLogLevel level : AgentLogLevel.values()) {
            assertRoundTrip(envelope(1L, uuid(), TracingSettings.of(
                    true, 0.5, Map.of("/a", 0.1),
                    SpanAttributeValidationLevel.WARNING, true, false, level)));
        }
    }

    @Test
    void routeMappingIsDeterministic() {
        Map<String, Double> insertedAscending = new LinkedHashMap<>();
        insertedAscending.put("/a", 0.1);
        insertedAscending.put("/m", 0.5);
        insertedAscending.put("/z", 0.9);
        Map<String, Double> insertedDescending = new LinkedHashMap<>();
        insertedDescending.put("/z", 0.9);
        insertedDescending.put("/m", 0.5);
        insertedDescending.put("/a", 0.1);

        AcceptedSettings first = roundTrip(envelope(1L, uuid(), TracingSettings.of(
                true, 0.5, insertedAscending, SpanAttributeValidationLevel.WARNING, true, true, AgentLogLevel.INFO)));
        AcceptedSettings second = roundTrip(envelope(1L, uuid(), TracingSettings.of(
                true, 0.5, insertedDescending, SpanAttributeValidationLevel.WARNING, true, true, AgentLogLevel.INFO)));

        assertThat(first.policy().samplingRouteRatios())
                .isEqualTo(second.policy().samplingRouteRatios());
    }

    @ParameterizedTest
    @CsvSource({
            "true, DISABLED, false, false",
            "false, ERROR, false, false",
            "false, WARNING, false, false",
            "false, DISABLED, true, false",
            "false, DISABLED, false, true"
    })
    void singleLeafRoundTripsThroughRealAgentEncoder(boolean samplingEnabled,
                                                     SpanAttributeValidationLevel validationLevel,
                                                     boolean exporterEnabled,
                                                     boolean traceControlHeadersEnabled) {
        TracingSettings policy = TracingSettings.of(
                samplingEnabled, 0.5, Map.of(),
                validationLevel, exporterEnabled, traceControlHeadersEnabled,
                AgentLogLevel.INFO);
        CompleteTracingSettings snapshot = roundTrip(envelope(1L, uuid(), policy)).policy();

        assertThat(snapshot.samplingEnabled()).isEqualTo(samplingEnabled);
        assertThat(snapshot.validationLevel()).isEqualTo(validationLevel.name());
        assertThat(snapshot.exporterEnabled()).isEqualTo(exporterEnabled);
        assertThat(snapshot.traceControlHeadersEnabled()).isEqualTo(traceControlHeadersEnabled);
    }

    private static void assertRoundTrip(TracingSettingsRevision envelope) {
        AcceptedSettings decoded = roundTrip(envelope);

        assertThat(decoded.revision().acceptedSequence()).isEqualTo(envelope.acceptedSequence());
        assertThat(decoded.revision().agentInstanceId()).isEqualTo(envelope.agentInstanceId().toString());
        assertThat(decoded.authority().name()).isEqualTo(envelope.sourceAuthority().name());
        CompleteTracingSettings snapshot = decoded.policy();
        assertThat(snapshot.samplingEnabled()).isEqualTo(envelope.samplingEnabled());
        assertThat(snapshot.samplingRatio()).isEqualTo(envelope.samplingRatio());
        assertThat(snapshot.samplingRouteRatios()).isEqualTo(envelope.samplingRouteRatios());
        assertThat(snapshot.validationLevel()).isEqualTo(envelope.validationLevel().name());
        assertThat(snapshot.exporterEnabled()).isEqualTo(envelope.exporterEnabled());
        assertThat(snapshot.traceControlHeadersEnabled()).isEqualTo(envelope.traceControlHeadersEnabled());
        assertThat(snapshot.diagnosticsLogLevel()).isEqualTo(envelope.diagnosticsLogLevel());
    }

    private static AcceptedSettings roundTrip(TracingSettingsRevision envelope) {
        TracingSettingsUpdater updater = mock(TracingSettingsUpdater.class);
        when(updater.current()).thenReturn(envelope);
        PlatformSettingsStatusMBean status = new PlatformSettingsStatusMBean(updater);
        try {
            CompositeData wire = (CompositeData) status.getAttribute(
                    JmxWireContract.ATTRIBUTE_SETTINGS_STATUS_ACCEPTED_POLICY_SNAPSHOT);
            new JmxWireGraphValidator().validate(wire);
            return AcceptedPolicySnapshotDecoder.decode(wire);
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot read encoded accepted-policy snapshot", exception);
        }
    }

    private static TracingSettingsRevision envelope(long sequence, String agentInstanceId, TracingSettings policy) {
        return envelope(sequence, agentInstanceId, policy, SettingsSourceAuthority.CONFIG_SERVER);
    }

    private static TracingSettingsRevision envelope(long sequence,
                                                    String agentInstanceId,
                                                    TracingSettings policy,
                                                    SettingsSourceAuthority authority) {
        return new TracingSettingsRevision(
                AgentInstanceId.parse(agentInstanceId),
                sequence,
                policy,
                authority
        );
    }

    private static Map<String, Double> maximumRouteRatios() {
        Map<String, Double> routes = new LinkedHashMap<>();
        for (int index = 0; index < JmxWireContract.MAX_ROUTE_ENTRIES; index++) {
            routes.put("/" + "%02d".formatted(index) + "x".repeat(252), 0.5d);
        }
        return routes;
    }

    private static String uuid() {
        return UUID.randomUUID().toString();
    }
}
