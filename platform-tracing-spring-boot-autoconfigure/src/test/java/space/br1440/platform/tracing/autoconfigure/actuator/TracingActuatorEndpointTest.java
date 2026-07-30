package space.br1440.platform.tracing.autoconfigure.actuator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import space.br1440.platform.tracing.autoconfigure.TracingProperties;
import space.br1440.platform.tracing.autoconfigure.diagnostics.SpanFactoryDiagnostics;
import space.br1440.platform.tracing.autoconfigure.jmx.PlatformTracingJmxClient;
import space.br1440.platform.tracing.otel.facade.NoopTraceOperations;
import space.br1440.platform.tracing.otel.runtime.NoOpTracingRuntime;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * P6.4: legacy {@code @WriteOperation} / mutation-enabled gate deleted.
 * This endpoint remains a read-only diagnostics projection.
 */
class TracingActuatorEndpointTest {

    private TracingProperties properties;
    private PlatformTracingJmxClient jmxClient;
    private TracingActuatorEndpoint endpoint;

    @BeforeEach
    void setUp() {
        properties = new TracingProperties();
        jmxClient = Mockito.mock(PlatformTracingJmxClient.class);
        when(jmxClient.isAvailable()).thenReturn(true);
        when(jmxClient.getCurrentRatio()).thenReturn(Optional.of(0.1d));
        endpoint = new TracingActuatorEndpoint(
                NoopTraceOperations.INSTANCE,
                properties,
                jmxClient,
                new SpanFactoryDiagnostics(NoOpTracingRuntime.noop()),
                null);
    }

    @Test
    void endpoint_hasNoWriteOperationMethods() {
        boolean hasWrite = Arrays.stream(TracingActuatorEndpoint.class.getDeclaredMethods())
                .map(Method::getAnnotations)
                .flatMap(Arrays::stream)
                .anyMatch(annotation -> annotation.annotationType().getSimpleName().equals("WriteOperation"));
        assertThat(hasWrite).isFalse();
        assertThat(TracingActuatorEndpoint.class.getDeclaredMethods())
                .extracting(Method::getName)
                .doesNotContain("updateTracing");
    }

    @Test
    void readOperation_формирует_сокращённый_read_model_без_mutation_gate() {
        Map<String, Object> info = endpoint.tracing();

        assertThat(info).containsKeys("mode", "implementation", "sampling", "validation",
                "otelEffective", "resourceEffective", "export", "processors", "config");
        assertThat(info).doesNotContainKeys(
                "actuator", "limits", "queue", "exporter", "enriching", "watchdog",
                "control", "otelEnvHints", "resourceSpringConfig");

        @SuppressWarnings("unchecked")
        Map<String, Object> service = (Map<String, Object>) info.get("service");
        assertThat(service).containsOnlyKeys("name");
    }

    @Test
    void readOperation_validation_не_содержит_strictRuntimeAllowedNote() {
        Map<String, Object> info = endpoint.tracing();
        @SuppressWarnings("unchecked")
        Map<String, Object> validation = (Map<String, Object>) info.get("validation");

        assertThat(validation).doesNotContainKey("strictRuntimeAllowed");
        assertThat(validation).doesNotContainKey("strictRuntimeAllowedNote");
        assertThat(validation).containsKeys("enabled", "strict", "liveStrictRuntimeAllowed");
    }

    @Test
    void readOperation_возвращает_текущее_состояние_включая_live_ratio() {
        Map<String, Object> info = endpoint.tracing();
        assertThat(info).containsKeys("mode", "implementation", "sampling", "validation");

        @SuppressWarnings("unchecked")
        Map<String, Object> sampling = (Map<String, Object>) info.get("sampling");
        assertThat(sampling).containsEntry("ratio", 0.1d);
        assertThat(sampling).containsEntry("liveRatio", 0.1d);
        assertThat(sampling).containsEntry("controlAvailable", true);
        assertThat(sampling).doesNotContainKeys(
                "dropPaths", "forceRecordHeaderValues", "forceRecordHeader", "qaForceHeader");
    }

    @Test
    void readOperation_содержит_секции_validation_и_scrubbing() {
        Map<String, Object> info = endpoint.tracing();

        @SuppressWarnings("unchecked")
        Map<String, Object> validation = (Map<String, Object>) info.get("validation");
        assertThat(validation).containsEntry("enabled", true);
        assertThat(validation).containsEntry("strict", false);
        assertThat(validation).containsKeys("liveEnabled", "liveStrict", "configVersion", "configSource");

        @SuppressWarnings("unchecked")
        Map<String, Object> scrubbing = (Map<String, Object>) info.get("scrubbing");
        assertThat(scrubbing).containsKeys("liveEnabled", "configVersion", "configSource", "liveRuleCount");
        assertThat(scrubbing).containsEntry("customRulesSource", "otel-agent-spi");
        assertThat(scrubbing).containsEntry("customRulesVisible", false);
        assertThat(scrubbing).containsKey("note");
        assertThat(scrubbing).doesNotContainKeys("enabled", "builtInRules", "rulesConfig");
    }

    @Test
    void readOperation_validation_reflects_live_agent_state() {
        when(jmxClient.isValidationEnabled()).thenReturn(Optional.of(true));
        when(jmxClient.isValidationStrict()).thenReturn(Optional.of(true));
        when(jmxClient.getValidationConfigVersion()).thenReturn(Optional.of(5L));
        when(jmxClient.getValidationConfigLastUpdatedSource()).thenReturn(Optional.of("spring-runtime-config"));
        properties.getValidation().setStrict(false);

        Map<String, Object> info = endpoint.tracing();
        @SuppressWarnings("unchecked")
        Map<String, Object> validation = (Map<String, Object>) info.get("validation");

        assertThat(validation).containsEntry("strict", false);
        assertThat(validation).containsEntry("liveStrict", true);
        assertThat(validation).containsEntry("configVersion", 5L);
        assertThat(validation).containsEntry("configSource", "spring-runtime-config");
    }

    @Test
    void readOperation_sampling_scrubbing_live_fields_present() {
        when(jmxClient.getLiveDropPathPrefixes()).thenReturn(Optional.of(java.util.List.of("/health")));
        when(jmxClient.getLiveForceRecordValues()).thenReturn(Optional.of(java.util.List.of("on")));
        when(jmxClient.getLiveRouteRatios()).thenReturn(Optional.of(Map.of("/api", 0.5d)));
        when(jmxClient.getSamplingConfigLastUpdatedSource()).thenReturn(Optional.of("JMX"));
        when(jmxClient.isScrubbingEnabled()).thenReturn(Optional.of(false));
        when(jmxClient.getScrubbingConfigVersion()).thenReturn(Optional.of(3L));
        when(jmxClient.getScrubbingConfigLastUpdatedSource()).thenReturn(Optional.of("spring-runtime-config"));
        when(jmxClient.getScrubbingMetrics()).thenReturn(Map.of("rules.loaded", 2L));

        Map<String, Object> info = endpoint.tracing();
        @SuppressWarnings("unchecked")
        Map<String, Object> sampling = (Map<String, Object>) info.get("sampling");
        assertThat(sampling).containsKeys("enabled", "routeRatios", "liveDropPaths", "liveForceRecordHeaderValues",
                "liveRouteRatios", "configSource");

        @SuppressWarnings("unchecked")
        Map<String, Object> scrubbing = (Map<String, Object>) info.get("scrubbing");
        assertThat(scrubbing).containsEntry("liveEnabled", false);
        assertThat(scrubbing).containsEntry("configVersion", 3L);
        assertThat(scrubbing).containsEntry("liveRuleCount", 2L);
    }

    @Test
    void readOperation_export_секция_not_ready_когда_метрики_недоступны() {
        when(jmxClient.getExportMetrics()).thenReturn(Optional.empty());

        Map<String, Object> info = endpoint.tracing();

        @SuppressWarnings("unchecked")
        Map<String, Object> export = (Map<String, Object>) info.get("export");
        assertThat(export).containsEntry("status", "not_ready");
    }

    @Test
    void readOperation_export_секция_содержит_метрики_когда_доступны() {
        Map<String, Object> metrics = Map.of(
                "queueCapacity", 2048,
                "queueSize", 10,
                "failures", 3L);
        when(jmxClient.getExportMetrics()).thenReturn(Optional.of(metrics));

        Map<String, Object> info = endpoint.tracing();

        @SuppressWarnings("unchecked")
        Map<String, Object> export = (Map<String, Object>) info.get("export");
        assertThat(export)
                .containsEntry("queueCapacity", 2048)
                .containsEntry("queueSize", 10)
                .containsEntry("failures", 3L);
    }

    @Test
    void readOperation_когда_расширение_недоступно_отдаёт_null_live_ratio() {
        when(jmxClient.isAvailable()).thenReturn(false);
        when(jmxClient.getCurrentRatio()).thenReturn(Optional.empty());

        Map<String, Object> info = endpoint.tracing();
        @SuppressWarnings("unchecked")
        Map<String, Object> sampling = (Map<String, Object>) info.get("sampling");
        assertThat(sampling).containsEntry("liveRatio", null);
        assertThat(sampling).containsEntry("controlAvailable", false);
    }

    @Test
    void readOperation_содержит_секцию_otelEffective_с_подменой_источников() {
        java.util.Map<String, String> sysProps = new java.util.HashMap<>();
        java.util.Map<String, String> envVars = new java.util.HashMap<>();
        sysProps.put("otel.bsp.max.queue.size", "8192");
        envVars.put("OTEL_EXPORTER_OTLP_ENDPOINT", "http://collector-test:4317");
        envVars.put("OTEL_EXPORTER_OTLP_HEADERS", "authorization=Bearer secret");

        OtelEffectiveConfigSnapshot snapshot = new OtelEffectiveConfigSnapshot(
                sysProps::get, envVars::get);
        ResourceEffectiveSnapshot resourceSnapshot = new ResourceEffectiveSnapshot(
                sysProps::get, envVars::get);
        TracingActuatorEndpoint custom = new TracingActuatorEndpoint(
                NoopTraceOperations.INSTANCE,
                properties,
                jmxClient,
                snapshot,
                resourceSnapshot,
                new SpanFactoryDiagnostics(NoOpTracingRuntime.noop()),
                null);

        Map<String, Object> info = custom.tracing();
        assertThat(info).containsKey("otelEffective");

        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> effective =
                (Map<String, Map<String, Object>>) info.get("otelEffective");

        assertThat(effective.get("otel.bsp.max.queue.size"))
                .containsEntry("source", "system-property")
                .containsEntry("value", "8192");

        assertThat(effective.get("otel.exporter.otlp.endpoint"))
                .containsEntry("source", "env-var")
                .containsEntry("value", "http://collector-test:4317");

        assertThat(effective.get("otel.exporter.otlp.headers"))
                .containsEntry("source", "env-var")
                .containsEntry("value", "***");

        assertThat(effective.get("otel.span.attribute.count.limit"))
                .containsEntry("source", "default-platform")
                .containsEntry("value", "50");
    }

    @Test
    void readOperation_config_lastUpdatedSource_legacy_sampling_only() {
        when(jmxClient.getSamplingConfigLastUpdatedSource()).thenReturn(Optional.of("JMX"));
        when(jmxClient.getValidationConfigLastUpdatedSource())
                .thenReturn(Optional.of("spring-runtime-config"));

        Map<String, Object> info = endpoint.tracing();
        @SuppressWarnings("unchecked")
        Map<String, Object> config = (Map<String, Object>) info.get("config");
        @SuppressWarnings("unchecked")
        Map<String, Object> validation = (Map<String, Object>) info.get("validation");

        assertThat(config.get("lastUpdatedSource")).isEqualTo("JMX");
        assertThat(validation.get("configSource")).isEqualTo("spring-runtime-config");
    }
}
