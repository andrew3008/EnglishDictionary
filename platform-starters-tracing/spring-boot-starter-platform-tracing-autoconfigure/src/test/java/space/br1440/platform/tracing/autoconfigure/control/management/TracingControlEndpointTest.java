package space.br1440.platform.tracing.autoconfigure.control.management;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.endpoint.web.WebEndpointResponse;

import space.br1440.platform.tracing.autoconfigure.control.settings.CompleteTracingSettings;
import space.br1440.platform.tracing.autoconfigure.control.settings.ExpectedSettingsRevision;
import space.br1440.platform.tracing.autoconfigure.control.settings.delivery.TracingSettingsApplier;
import space.br1440.platform.tracing.autoconfigure.control.settings.delivery.TracingSettingsDeliveryResult;
import space.br1440.platform.tracing.autoconfigure.control.settings.delivery.TracingSettingsDeliverySource;
import space.br1440.platform.tracing.autoconfigure.control.settings.read.AcceptedSettingsWithSequence;
import space.br1440.platform.tracing.autoconfigure.control.settings.read.CompleteTracingSettingsReader;

class TracingControlEndpointTest {

    private CapturingApplier applier;
    private TracingControlEndpoint endpoint;

    @BeforeEach
    void setUp() {
        CompleteTracingSettings initial = CompleteTracingSettings.builder()
                .samplingEnabled(true)
                .samplingRatio(1.0)
                .samplingRouteRatios(Map.of("checkout", 0.5))
                .validationEnabled(true)
                .validationStrict(false)
                .exporterEnabled(true)
                .traceControlHeadersEnabled(true)
                .diagnosticsLogLevel("INFO")
                .build();
        CompleteTracingSettingsReader reader = () -> Optional.of(
                new AcceptedSettingsWithSequence(10L, "test-runtime", initial));
        applier = new CapturingApplier();
        endpoint = new TracingControlEndpoint(new TracingSettingsMutationService(reader, applier));
    }

    @Test
    void appliesAllTypedOperations() {
        assertApplied(endpoint.update("sampling", "enabled", false, null, null, null, null),
                "sampling.enabled", false);
        assertApplied(endpoint.update("sampling", "ratio", null, 0.5, null, null, null),
                "sampling.ratio", 0.5);
        assertApplied(endpoint.update("sampling", "route-ratios", null, null, "{\"api\":0.8}", null, null),
                "sampling.route-ratios", 1);
        assertApplied(endpoint.update("validation", "enabled", false, null, null, null, null),
                "validation.enabled", false);
        assertApplied(endpoint.update("validation", "strict", null, null, null, true, null),
                "validation.strict", true);
        assertApplied(endpoint.update("exporter", "enabled", false, null, null, null, null),
                "exporter.enabled", false);
        assertApplied(endpoint.update(
                        "propagation", "trace-control-headers-enabled", false, null, null, null, null),
                "propagation.trace-control-headers.enabled", false);
        assertApplied(endpoint.update("diagnostics", "log-level", null, null, null, null, "DEBUG"),
                "diagnostics.log-level", "DEBUG");

        assertThat(applier.applyCount).isEqualTo(8);
    }

    @Test
    void mapsDeliveryOutcomesToHttpStatuses() {
        applier.result = TracingSettingsDeliveryResult.REJECTED;
        assertThat(updateSampling()).extracting(WebEndpointResponse::getStatus).isEqualTo(422);

        applier.result = TracingSettingsDeliveryResult.STALE;
        assertThat(updateSampling()).extracting(WebEndpointResponse::getStatus).isEqualTo(409);

        applier.result = TracingSettingsDeliveryResult.FAILED;
        assertThat(updateSampling()).extracting(WebEndpointResponse::getStatus).isEqualTo(503);

        applier.result = TracingSettingsDeliveryResult.UNCHANGED;
        assertThat(updateSampling()).extracting(WebEndpointResponse::getStatus).isEqualTo(200);
    }

    @Test
    void rejectsMissingInputBeforeMutation() {
        WebEndpointResponse<Map<String, Object>> response =
                endpoint.update("sampling", "enabled", null, null, null, null, null);

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getBody())
                .containsEntry("outcome", "REJECTED")
                .containsEntry("leaf", "sampling.enabled")
                .containsEntry("error", "Required request parameter is missing: enabled");
        assertThat(applier.applyCount).isZero();
    }

    @Test
    void rejectsUnsupportedOperation() {
        WebEndpointResponse<Map<String, Object>> response =
                endpoint.update("sampling", "unknown", true, null, null, null, null);

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(response.getBody())
                .containsEntry("outcome", "REJECTED")
                .containsEntry("leaf", "sampling.unknown");
        assertThat(applier.applyCount).isZero();
    }

    @Test
    void rejectsMalformedRouteRatios() {
        WebEndpointResponse<Map<String, Object>> response =
                endpoint.update("sampling", "route-ratios", null, null, "{\"api\":\"invalid\"}", null, null);

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getBody())
                .containsEntry("outcome", "REJECTED")
                .containsEntry("leaf", "sampling.route-ratios");
        assertThat(applier.applyCount).isZero();
    }

    private WebEndpointResponse<Map<String, Object>> updateSampling() {
        return endpoint.update("sampling", "enabled", true, null, null, null, null);
    }

    private static void assertApplied(
            WebEndpointResponse<Map<String, Object>> response, String leaf, Object value) {
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getBody())
                .containsEntry("outcome", "APPLIED")
                .containsEntry("leaf", leaf)
                .containsEntry("value", value);
    }

    private static final class CapturingApplier implements TracingSettingsApplier {

        private TracingSettingsDeliveryResult result = TracingSettingsDeliveryResult.APPLIED;
        private int applyCount;

        @Override
        public TracingSettingsDeliveryResult applySnapshot(
                ExpectedSettingsRevision expectedBase,
                CompleteTracingSettings snapshot,
                TracingSettingsDeliverySource source) {
            applyCount++;
            return result;
        }
    }
}
