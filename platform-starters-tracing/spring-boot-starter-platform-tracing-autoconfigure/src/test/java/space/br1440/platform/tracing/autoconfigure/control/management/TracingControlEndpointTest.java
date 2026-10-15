package space.br1440.platform.tracing.autoconfigure.control.management;

import static org.assertj.core.api.Assertions.assertThat;
import static space.br1440.platform.tracing.autoconfigure.support.RecordingSettingsWriter.recordingSettingsWriter;

import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.endpoint.web.WebEndpointResponse;

import space.br1440.platform.tracing.autoconfigure.control.settings.AcceptedSettings;
import space.br1440.platform.tracing.autoconfigure.control.settings.CompleteTracingSettings;
import space.br1440.platform.tracing.autoconfigure.control.settings.SettingsDelivery;
import space.br1440.platform.tracing.autoconfigure.control.settings.SettingsRevision;
import space.br1440.platform.tracing.autoconfigure.control.settings.SettingsWriter;
import space.br1440.platform.tracing.autoconfigure.control.settings.TracingSettingsDeliveryResult;
import space.br1440.platform.tracing.autoconfigure.support.AcceptedSettingsTestFixtures;
import space.br1440.platform.tracing.autoconfigure.support.RecordingSettingsWriter;

class TracingControlEndpointTest {

    private CapturingApplier applier;
    private TracingControlEndpoint endpoint;

    @BeforeEach
    void setUp() {
        CompleteTracingSettings initial = CompleteTracingSettings.builder()
                .samplingEnabled(true)
                .samplingRatio(1.0)
                .samplingRouteRatios(Map.of("checkout", 0.5))
                .validationLevel("WARNING")
                .exporterEnabled(true)
                .traceControlHeadersEnabled(true)
                .diagnosticsLogLevel("INFO")
                .build();
        Supplier<Optional<AcceptedSettings>> reader = () -> Optional.of(
                AcceptedSettingsTestFixtures.bootstrapAgentDefaults(
                        10L,
                        "d8d5f1af-fe12-4b2b-a05f-e3532b06329d",
                        initial
                ));
        applier = new CapturingApplier();
        endpoint = new TracingControlEndpoint(
                new TracingSettingsMutationService(recordingSettingsWriter(applier, reader))
        );
    }

    @Test
    void appliesAllTypedOperations() {
        assertApplied(endpoint.update("sampling", "enabled", false, null, null, null),
                "sampling.enabled", false);
        assertApplied(endpoint.update("sampling", "ratio", null, 0.5, null, null),
                "sampling.ratio", 0.5);
        assertApplied(endpoint.update("sampling", "route-ratios", null, null, "{\"api\":0.8}", null),
                "sampling.route-ratios", 1);
        assertApplied(endpoint.update("validation", "level", null, null, null, "ERROR"),
                "validation.level", "ERROR");
        assertApplied(endpoint.update("exporter", "enabled", false, null, null, null),
                "exporter.enabled", false);
        assertApplied(endpoint.update(
                        "propagation", "trace-control-headers-enabled", false, null, null, null),
                "propagation.trace-control-headers.enabled", false);
        assertApplied(endpoint.update("diagnostics", "log-level", null, null, null, "DEBUG"),
                "diagnostics.log-level", "DEBUG");

        assertThat(applier.applyCount).isEqualTo(7);
    }

    @Test
    void mapsDeliveryOutcomesToHttpStatuses() {
        applier.result = TracingSettingsDeliveryResult.REJECTED;
        applier.messageCode = "AGENT_INSTANCE_MISMATCH";
        assertThat(updateSampling()).extracting(WebEndpointResponse::getStatus).isEqualTo(422);

        applier.result = TracingSettingsDeliveryResult.STALE;
        applier.messageCode = "ACCEPTED_SEQUENCE_MISMATCH";
        assertThat(updateSampling()).extracting(WebEndpointResponse::getStatus).isEqualTo(409);

        applier.result = TracingSettingsDeliveryResult.FAILED;
        applier.messageCode = "INTERNAL_FAILURE";
        assertThat(updateSampling()).extracting(WebEndpointResponse::getStatus).isEqualTo(503);

        applier.result = TracingSettingsDeliveryResult.UNCHANGED;
        applier.messageCode = "NONE";
        assertThat(updateSampling()).extracting(WebEndpointResponse::getStatus).isEqualTo(200);
    }

    @Test
    void agentRejectionPreservesAttemptIdAndMessageCode() {
        // given
        applier.result = TracingSettingsDeliveryResult.REJECTED;
        applier.messageCode = "LIMIT_EXCEEDED";

        // when
        WebEndpointResponse<Map<String, Object>> response = updateSampling();

        // then
        assertThat(response.getStatus()).isEqualTo(422);
        assertThat(response.getBody())
                .containsEntry("outcome", "REJECTED")
                .containsEntry("attemptId", CapturingApplier.ATTEMPT_ID)
                .containsEntry("messageCode", "LIMIT_EXCEEDED");
    }

    @Test
    void invalidCandidateIsRejectedLocallyWithoutAttemptId() {
        // given
        // when
        WebEndpointResponse<Map<String, Object>> response =
                endpoint.update("sampling", "ratio", null, 1.1d, null, null);

        // then
        assertThat(response.getStatus()).isEqualTo(422);
        assertThat(response.getBody())
                .containsEntry("outcome", "REJECTED")
                .containsEntry("messageCode", "INVALID_VALUE")
                .doesNotContainKey("attemptId");
        assertThat(applier.applyCount).isZero();
    }

    @Test
    void rejectsMissingInputBeforeMutation() {
        WebEndpointResponse<Map<String, Object>> response =
                endpoint.update("sampling", "enabled", null, null, null, null);

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getBody())
                .containsEntry("outcome", "REJECTED")
                .containsEntry("leaf", "sampling.enabled")
                .containsEntry("messageCode", "INVALID_VALUE")
                .containsEntry("error", "Required request parameter is missing: enabled");
        assertThat(response.getBody()).doesNotContainKey("attemptId");
        assertThat(applier.applyCount).isZero();
    }

    @Test
    void rejectsUnsupportedOperation() {
        WebEndpointResponse<Map<String, Object>> response =
                endpoint.update("sampling", "unknown", true, null, null, null);

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(response.getBody())
                .containsEntry("outcome", "REJECTED")
                .containsEntry("leaf", "sampling.unknown")
                .containsEntry("messageCode", "INVALID_VALUE");
        assertThat(response.getBody()).doesNotContainKey("attemptId");
        assertThat(applier.applyCount).isZero();
    }

    @Test
    void rejectsMalformedRouteRatios() {
        WebEndpointResponse<Map<String, Object>> response =
                endpoint.update("sampling", "route-ratios", null, null, "{\"api\":\"invalid\"}", null);

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getBody())
                .containsEntry("outcome", "REJECTED")
                .containsEntry("leaf", "sampling.route-ratios")
                .containsEntry("messageCode", "INVALID_VALUE");
        assertThat(response.getBody()).doesNotContainKey("attemptId");
        assertThat(applier.applyCount).isZero();
    }

    private WebEndpointResponse<Map<String, Object>> updateSampling() {
        return endpoint.update("sampling", "enabled", true, null, null, null);
    }

    private static void assertApplied(
            WebEndpointResponse<Map<String, Object>> response, String leaf, Object value) {
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getBody())
                .containsEntry("outcome", "APPLIED")
                .containsEntry("leaf", leaf)
                .containsEntry("value", value)
                .containsEntry("attemptId", CapturingApplier.ATTEMPT_ID)
                .containsEntry("messageCode", "NONE");
    }

    private static final class CapturingApplier implements RecordingSettingsWriter.ApplyBehavior {

        private static final String ATTEMPT_ID = "00000000-0000-0000-0000-000000000001";

        private TracingSettingsDeliveryResult result = TracingSettingsDeliveryResult.APPLIED;
        private String messageCode = "NONE";
        private int applyCount;

        @Override
        public SettingsDelivery applySnapshot(
                SettingsRevision expectedBase,
                CompleteTracingSettings snapshot,
                SettingsWriter writer) {
            applyCount++;
            return new SettingsDelivery(ATTEMPT_ID, result, messageCode);
        }
    }
}
