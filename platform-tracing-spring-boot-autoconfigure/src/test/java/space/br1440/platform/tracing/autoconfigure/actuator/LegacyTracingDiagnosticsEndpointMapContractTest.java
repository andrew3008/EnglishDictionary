package space.br1440.platform.tracing.autoconfigure.actuator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import space.br1440.platform.tracing.autoconfigure.TracingProperties;
import space.br1440.platform.tracing.autoconfigure.diagnostics.SpanFactoryDiagnostics;
import space.br1440.platform.tracing.autoconfigure.jmx.PlatformTracingJmxClient;
import space.br1440.platform.tracing.otel.facade.NoopTraceOperations;
import space.br1440.platform.tracing.otel.runtime.NoOpTracingRuntime;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Executable endpoint Map contract for wave2 disclosure hardening.
 * <p>
 * Asserts {@code Map<String, Object>} from {@link LegacyTracingDiagnosticsEndpoint#tracing()},
 * not host Jackson HTTP serialization.
 */
class LegacyTracingDiagnosticsEndpointMapContractTest {

    private static final String CANARY_ROUTE = "/canary-route-secret-zz9";

    private PlatformTracingJmxClient jmxClient;
    private LegacyTracingDiagnosticsEndpoint endpoint;

    @BeforeEach
    void setUp() {
        jmxClient = Mockito.mock(PlatformTracingJmxClient.class);
        when(jmxClient.isAvailable()).thenReturn(true);
        when(jmxClient.getCurrentRatio()).thenReturn(Optional.of(0.1d));
        when(jmxClient.isSamplerEnabled()).thenReturn(Optional.of(true));
        when(jmxClient.getSamplingConfigVersion()).thenReturn(Optional.of(1L));
        when(jmxClient.getSamplingConfigLastUpdatedSource()).thenReturn(Optional.of("JMX"));
        when(jmxClient.getInvalidConfigCount()).thenReturn(Optional.of(0L));
        when(jmxClient.getSamplerDecisionCounts()).thenReturn(Collections.emptyMap());
        when(jmxClient.getExportMetrics()).thenReturn(Optional.empty());
        when(jmxClient.isScrubbingEnabled()).thenReturn(Optional.of(true));
        when(jmxClient.getScrubbingConfigVersion()).thenReturn(Optional.of(2L));
        when(jmxClient.getScrubbingConfigLastUpdatedSource()).thenReturn(Optional.of("JMX"));
        when(jmxClient.getScrubbingMetrics()).thenReturn(Map.of("rules.loaded", 3L));
        when(jmxClient.isValidationEnabled()).thenReturn(Optional.of(true));
        when(jmxClient.isValidationStrict()).thenReturn(Optional.of(false));
        when(jmxClient.isValidationStrictRuntimeAllowed()).thenReturn(Optional.of(true));
        when(jmxClient.getValidationConfigVersion()).thenReturn(Optional.of(4L));
        when(jmxClient.getValidationConfigLastUpdatedSource()).thenReturn(Optional.of("JMX"));
        when(jmxClient.getProcessorErrorsTotal()).thenReturn(Optional.of(0L));
        when(jmxClient.getProcessorErrorsByName()).thenReturn(Collections.emptyMap());
        when(jmxClient.getConfigReloadMetrics()).thenReturn(Map.of(
                "updates.applied", 1L,
                "updates.rejected", 0L,
                "last_update.epoch_ms", 100L));
        when(jmxClient.getConfigAuditTrail()).thenReturn(List.of("entry-1"));

        endpoint = new LegacyTracingDiagnosticsEndpoint(
                NoopTraceOperations.INSTANCE,
                new TracingProperties(),
                jmxClient,
                new SpanFactoryDiagnostics(NoOpTracingRuntime.noop()),
                null);
    }

    @Test
    void removedRawSamplingFieldsAndHardcodedScrubbingStrings() {
        stubCounts(0);

        Map<String, Object> info = endpoint.tracing();
        @SuppressWarnings("unchecked")
        Map<String, Object> sampling = (Map<String, Object>) info.get("sampling");
        @SuppressWarnings("unchecked")
        Map<String, Object> scrubbing = (Map<String, Object>) info.get("scrubbing");

        assertThat(sampling).doesNotContainKeys(
                "liveRouteRatios", "liveDropPaths", "liveForceRecordHeaderValues",
                "dropPathCount", "forceRecordValueCount");
        assertThat(scrubbing).doesNotContainKeys(
                "customRulesSource", "customRulesVisible", "note");
    }

    @Test
    void emptyCollection_reportsCountZero_notNull() {
        stubCounts(0);

        Map<String, Object> sampling = sampling(endpoint.tracing());
        assertThat(sampling).containsEntry("routeRatioCount", 0);
    }

    @Test
    void singleElementCollection_reportsCountOne() {
        stubCounts(1);

        Map<String, Object> sampling = sampling(endpoint.tracing());
        assertThat(sampling).containsEntry("routeRatioCount", 1);
    }

    @Test
    void routeRatioApprovedMaximum_reportsExactCount() {
        // Formal Category A / wire bound: SpringActuatorTracingPolicyAdapter.MAX_ROUTE_RATIOS = 100
        stubCounts(100);

        Map<String, Object> sampling = sampling(endpoint.tracing());
        assertThat(sampling).containsEntry("routeRatioCount", 100);
    }

    @Test
    void unavailableJmx_countFieldPresentWithNull_notZero() {
        when(jmxClient.getLiveRouteRatioCount()).thenReturn(Optional.empty());

        Map<String, Object> sampling = sampling(endpoint.tracing());
        assertThat(sampling).containsEntry("routeRatioCount", null);
        assertThat(sampling.get("routeRatioCount")).isNotEqualTo(0);
    }

    @Test
    void preservedAuditLiveRuleCountSamplingValidationSignals() {
        stubCounts(0);

        Map<String, Object> info = endpoint.tracing();
        @SuppressWarnings("unchecked")
        Map<String, Object> sampling = (Map<String, Object>) info.get("sampling");
        @SuppressWarnings("unchecked")
        Map<String, Object> scrubbing = (Map<String, Object>) info.get("scrubbing");
        @SuppressWarnings("unchecked")
        Map<String, Object> validation = (Map<String, Object>) info.get("validation");
        @SuppressWarnings("unchecked")
        Map<String, Object> config = (Map<String, Object>) info.get("config");

        assertThat(config).containsEntry("auditTrail", List.of("entry-1"));
        assertThat(scrubbing).containsEntry("liveRuleCount", 3L);
        assertThat(sampling).containsEntry("liveRatio", 0.1d);
        assertThat(sampling).containsEntry("liveSamplerEnabled", true);
        assertThat(sampling).containsEntry("controlAvailable", true);
        assertThat(sampling).containsEntry("configVersion", 1L);
        assertThat(sampling).containsEntry("configSource", "JMX");
        assertThat(validation).containsEntry("liveEnabled", true);
        assertThat(validation).containsEntry("liveStrict", false);
        assertThat(validation).containsEntry("configVersion", 4L);
    }

    @Test
    void canaryRawValuesAbsentFromMapPayload() {
        stubCounts(1);

        Map<String, Object> info = endpoint.tracing();
        String flattened = flatten(info);
        assertThat(flattened).doesNotContain(CANARY_ROUTE);
        assertThat(flattened).doesNotContain("otel-agent-spi");
    }

    private void stubCounts(int routes) {
        when(jmxClient.getLiveRouteRatioCount()).thenReturn(Optional.of(routes));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> sampling(Map<String, Object> info) {
        return (Map<String, Object>) info.get("sampling");
    }

    private static String flatten(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Map<?, ?> map) {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                sb.append(e.getKey()).append('=').append(flatten(e.getValue())).append(';');
            }
            return sb.toString();
        }
        if (value instanceof Iterable<?> it) {
            StringBuilder sb = new StringBuilder();
            for (Object o : it) {
                sb.append(flatten(o)).append(',');
            }
            return sb.toString();
        }
        return String.valueOf(value);
    }
}
