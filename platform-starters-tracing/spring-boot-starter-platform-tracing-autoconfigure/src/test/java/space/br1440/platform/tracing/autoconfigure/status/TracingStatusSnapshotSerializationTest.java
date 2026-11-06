package space.br1440.platform.tracing.autoconfigure.status;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import space.br1440.platform.tracing.autoconfigure.bootstrap.TracingControlChannel;
import space.br1440.platform.tracing.autoconfigure.control.settings.CompleteTracingSettings;
import space.br1440.platform.tracing.autoconfigure.control.settings.SettingsRevision;

class TracingStatusSnapshotSerializationTest {

    private static final String AGENT_INSTANCE_ID = "128bcc0c-8f1f-499d-9cbb-6ca73a99dc17";

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void acceptedSettingsRemainVisibleInActuatorJson() throws Exception {
        // given
        TracingStatusSnapshot snapshot = snapshot(CompleteTracingSettings.helmBootstrapDefaults(), 3L);

        // when
        JsonNode json = mapper.readTree(mapper.writeValueAsBytes(snapshot));

        // then
        assertThat(json.path("agent").path("lifecycle").asText()).isEqualTo("READY");
        assertThat(json.path("controlChannel").asText()).isEqualTo("NONE");
        assertThat(json.path("settings").path("available").asBoolean()).isTrue();
        assertThat(json.path("settings").path("acceptedRevision").path("acceptedSequence").asLong()).isEqualTo(3L);
        JsonNode accepted = json.path("settings").path("accepted");
        assertThat(accepted.fieldNames()).toIterable().containsExactlyInAnyOrder(
                "samplingEnabled",
                "samplingRatio",
                "samplingRouteRatios",
                "validationLevel",
                "exporterEnabled",
                "traceControlHeadersEnabled",
                "diagnosticsLogLevel");
        assertThat(accepted.path("samplingEnabled").asBoolean()).isTrue();
        assertThat(accepted.path("samplingRatio").asDouble()).isEqualTo(1.0d);
        assertThat(accepted.path("samplingRouteRatios").isObject()).isTrue();
        assertThat(accepted.path("samplingRouteRatios").size()).isZero();
        assertThat(accepted.path("validationLevel").asText()).isEqualTo("WARNING");
        assertThat(accepted.path("exporterEnabled").asBoolean()).isTrue();
        assertThat(accepted.path("traceControlHeadersEnabled").asBoolean()).isTrue();
        assertThat(accepted.path("diagnosticsLogLevel").asText()).isEqualTo("INFO");
    }

    @Test
    void routeRatiosStayInAcceptedSettings() throws Exception {
        // given
        CompleteTracingSettings settings = CompleteTracingSettings.builder()
                .samplingEnabled(true)
                .samplingRatio(1.0d)
                .samplingRouteRatios(Map.of("/work/orders", 1.0d, "/work/health", 0.0d))
                .validationLevel("DISABLED")
                .exporterEnabled(true)
                .traceControlHeadersEnabled(true)
                .diagnosticsLogLevel("INFO")
                .build();
        TracingStatusSnapshot snapshot = snapshot(settings, 4L);

        // when
        JsonNode accepted = mapper.readTree(mapper.writeValueAsBytes(snapshot))
                .path("settings")
                .path("accepted");

        // then
        assertThat(accepted.path("samplingRouteRatios").path("/work/orders").asDouble()).isEqualTo(1.0d);
        assertThat(accepted.path("samplingRouteRatios").path("/work/health").asDouble()).isEqualTo(0.0d);
        assertThat(accepted.path("validationLevel").asText()).isEqualTo("DISABLED");
        assertThat(accepted.fieldNames()).toIterable().doesNotContain(
                "withSamplingEnabled",
                "withSamplingRatio",
                "withSamplingRouteRatios",
                "withValidationLevel",
                "withExporterEnabled",
                "withTraceControlHeadersEnabled",
                "withDiagnosticsLogLevel");
    }

    private static TracingStatusSnapshot snapshot(CompleteTracingSettings accepted, long sequence) {
        return new TracingStatusSnapshot(
                true,
                TracingControlChannel.NONE,
                Map.of("lifecycle", "READY"),
                Map.of(),
                TracingStatusSnapshot.SettingsStatus.available(
                        new SettingsRevision(AGENT_INSTANCE_ID, sequence),
                        accepted,
                        "BOOTSTRAP"),
                List.of());
    }
}
