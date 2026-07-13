package space.br1440.platform.tracing.autoconfigure.actuator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import space.br1440.platform.tracing.autoconfigure.TracingProperties;
import space.br1440.platform.tracing.autoconfigure.diagnostics.ManualTracingDiagnostics;
import space.br1440.platform.tracing.autoconfigure.jmx.PlatformTracingJmxClient;
import space.br1440.platform.tracing.autoconfigure.jmx.PlatformTracingJmxOperationException;
import space.br1440.platform.tracing.core.facade.NoopTraceOperations;
import space.br1440.platform.tracing.core.runtime.NoOpTracingRuntime;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TracingActuatorEndpointTest {

    private TracingProperties properties;
    private PlatformTracingJmxClient jmxClient;
    private TracingActuatorEndpoint endpoint;

    @BeforeEach
    void setUp() {
        properties = new TracingProperties();
        properties.getActuator().setMutationEnabled(true);
        jmxClient = Mockito.mock(PlatformTracingJmxClient.class);
        when(jmxClient.isAvailable()).thenReturn(true);
        when(jmxClient.getCurrentRatio()).thenReturn(Optional.of(0.1d));
        endpoint = new TracingActuatorEndpoint(
                NoopTraceOperations.INSTANCE,
                properties,
                jmxClient,
                new ManualTracingDiagnostics(NoOpTracingRuntime.noop()));
    }

    @Test
    void writeOperation_по_умолчанию_mutation_disabled_отклоняет_и_не_вызывает_JMX() {
        properties.getActuator().setMutationEnabled(false);

        assertThatThrownBy(() -> endpoint.updateTracing("samplingRatio", "0.5"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN))
                .hasMessageContaining("mutation is disabled")
                .hasMessageContaining("platform.tracing.actuator.mutation-enabled=true");

        verify(jmxClient, never()).setRatio(anyDouble());
        assertThat(properties.getSampling().getRatio()).isEqualTo(0.1d);
    }

    @Test
    void writeOperation_mutation_disabled_отклоняет_enabled_без_изменения_properties() {
        properties.getActuator().setMutationEnabled(false);

        assertThatThrownBy(() -> endpoint.updateTracing("enabled", "false"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));

        assertThat(properties.isEnabled()).isTrue();
    }

    @Test
    void readOperation_содержит_actuator_mutationEnabled() {
        properties.getActuator().setMutationEnabled(false);

        Map<String, Object> info = endpoint.tracing();
        @SuppressWarnings("unchecked")
        Map<String, Object> actuator = (Map<String, Object>) info.get("actuator");

        assertThat(actuator).containsEntry("mutationEnabled", false);
    }

    @Test
    void readOperation_работает_когда_mutation_disabled() {
        properties.getActuator().setMutationEnabled(false);

        Map<String, Object> info = endpoint.tracing();
        assertThat(info).containsKeys("enabled", "sampling", "validation", "actuator");
    }

    @Test
    void readOperation_validation_contains_strictRuntimeAllowed_drift_note() {
        Map<String, Object> info = endpoint.tracing();
        @SuppressWarnings("unchecked")
        Map<String, Object> validation = (Map<String, Object>) info.get("validation");

        assertThat(validation).containsKey("strictRuntimeAllowedNote");
        assertThat((String) validation.get("strictRuntimeAllowedNote"))
                .contains("liveStrictRuntimeAllowed");
    }

    @Test
    void writeOperation_enabled_меняет_свойство() {
        Map<String, Object> result = endpoint.updateTracing("enabled", "false");
        assertThat(result.get("appliedValue")).isEqualTo(false);
        assertThat(result.get("previousValue")).isEqualTo(true);
        assertThat(properties.isEnabled()).isFalse();
        verify(jmxClient, never()).setRatio(anyDouble());
    }

    @Test
    void writeOperation_samplingRatio_проксирует_в_JMX_и_синхронизирует_properties() {
        Map<String, Object> result = endpoint.updateTracing("samplingRatio", "0.5");

        verify(jmxClient, times(1)).setRatio(0.5d);
        assertThat(result.get("appliedValue")).isEqualTo(0.5);
        assertThat(result.get("previousLiveValue")).isEqualTo(0.1d);
        assertThat(result.get("previousConfiguredValue")).isEqualTo(0.1d);
        assertThat(properties.getSampling().getRatio()).isEqualTo(0.5);
    }

    @Test
    void writeOperation_samplingRatio_когда_расширение_недоступно_отказывает_до_изменения_properties() {
        doThrow(new PlatformTracingJmxOperationException("sampling domain not available"))
                .when(jmxClient).setRatio(anyDouble());

        assertThatThrownBy(() -> endpoint.updateTracing("samplingRatio", "0.5"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));

        assertThat(properties.getSampling().getRatio()).isEqualTo(0.1d);
    }

    @Test
    void writeOperation_samplingRatio_за_пределами_диапазона_бросает_ResponseStatusException() {
        assertThatThrownBy(() -> endpoint.updateTracing("samplingRatio", "1.5"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining("samplingRatio")
                .hasMessageContaining("[0.0, 1.0]");
        assertThat(properties.getSampling().getRatio()).isEqualTo(0.1);
        verify(jmxClient, never()).setRatio(anyDouble());
    }

    @Test
    void writeOperation_samplingRatio_не_число_бросает_ResponseStatusException() {
        assertThatThrownBy(() -> endpoint.updateTracing("samplingRatio", "not-a-number"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining("samplingRatio must be a number")
                .hasCauseInstanceOf(NumberFormatException.class);
        assertThat(properties.getSampling().getRatio()).isEqualTo(0.1);
        verify(jmxClient, never()).setRatio(anyDouble());
    }

    @Test
    void writeOperation_exportEnabled_проксирует_в_JMX_и_синхронизирует_properties() {
        Map<String, Object> result = endpoint.updateTracing("exportEnabled", "false");

        verify(jmxClient, times(1)).setExportEnabled(false);
        assertThat(result.get("appliedValue")).isEqualTo(false);
        assertThat(properties.getExporter().isEnabled()).isFalse();
    }

    @Test
    void writeOperation_propagationEnabled_проксирует_в_JMX_и_синхронизирует_properties() {
        Map<String, Object> result = endpoint.updateTracing("propagationEnabled", "false");

        verify(jmxClient, times(1)).setPropagationEnabled(false);
        assertThat(result.get("appliedValue")).isEqualTo(false);
        assertThat(properties.getPropagation().isEnabled()).isFalse();
    }

    @Test
    void writeOperation_logLevel_проксирует_в_JMX_и_синхронизирует_properties() {
        Map<String, Object> result = endpoint.updateTracing("logLevel", "DEBUG");

        verify(jmxClient, times(1)).setPlatformLogLevel("DEBUG");
        assertThat(result.get("appliedValue")).isEqualTo("DEBUG");
        assertThat(properties.getDiagnostics().getLogLevel()).isEqualTo("DEBUG");
    }

    @Test
    void writeOperation_неизвестный_параметр_бросает_ResponseStatusException() {
        assertThatThrownBy(() -> endpoint.updateTracing("foobar", "any"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining("Unsupported tracing property: foobar");
        verify(jmxClient, never()).setRatio(anyDouble());
    }

    @Test
    void readOperation_возвращает_текущее_состояние_включая_live_ratio() {
        Map<String, Object> info = endpoint.tracing();
        assertThat(info).containsKeys("enabled", "implementation", "sampling", "limits", "queue", "exporter",
                "enriching", "validation", "watchdog");

        @SuppressWarnings("unchecked")
        Map<String, Object> sampling = (Map<String, Object>) info.get("sampling");
        assertThat(sampling).containsEntry("ratio", 0.1d);
        assertThat(sampling).containsEntry("liveRatio", 0.1d);
        assertThat(sampling).containsEntry("controlAvailable", true);
        assertThat(sampling).containsKey("dropPaths");
    }

    @Test
    void readOperation_содержит_секции_enriching_validation_watchdog_и_scrubbing() {
        Map<String, Object> info = endpoint.tracing();

        @SuppressWarnings("unchecked")
        Map<String, Object> enriching = (Map<String, Object>) info.get("enriching");
        assertThat(enriching).containsEntry("enabled", true);
        assertThat(enriching).containsKey("remoteServicePriority");

        @SuppressWarnings("unchecked")
        Map<String, Object> validation = (Map<String, Object>) info.get("validation");
        assertThat(validation).containsEntry("enabled", true);
        assertThat(validation).containsEntry("strict", false);
        assertThat(validation).containsKeys("liveEnabled", "liveStrict", "configVersion", "configSource");

        @SuppressWarnings("unchecked")
        Map<String, Object> watchdog = (Map<String, Object>) info.get("watchdog");
        assertThat(watchdog).containsEntry("enabled", true);
        assertThat(watchdog).containsKeys("scanInterval", "spanTimeout", "traceTimeout");

        @SuppressWarnings("unchecked")
        Map<String, Object> scrubbing = (Map<String, Object>) info.get("scrubbing");
        assertThat(scrubbing).containsEntry("enabled", true);
        assertThat(scrubbing).containsKeys("liveEnabled", "configVersion", "configSource", "liveRuleCount");
        assertThat(scrubbing).containsEntry("customRulesSource", "otel-agent-spi");
        assertThat(scrubbing).containsEntry("customRulesVisible", false);
        assertThat(scrubbing).containsKey("builtInRules");
        assertThat(scrubbing).containsKey("note");
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
                new ManualTracingDiagnostics(NoOpTracingRuntime.noop()));

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

    @Test
    void readOperation_содержит_otelEnvHints_из_TracingProperties() {
        properties.getQueue().setExportTimeout(java.time.Duration.ofMillis(100));

        Map<String, Object> info = endpoint.tracing();

        assertThat(info).containsKey("otelEnvHints");
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> hints =
                (Map<String, Map<String, Object>>) info.get("otelEnvHints");

        assertThat(hints.get("OTEL_BSP_EXPORT_TIMEOUT"))
                .containsEntry("suggestedValue", "100")
                .containsEntry("springProperty", "platform.tracing.queue.export-timeout");
    }
}
