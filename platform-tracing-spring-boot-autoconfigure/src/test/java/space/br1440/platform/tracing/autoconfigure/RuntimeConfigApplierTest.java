package space.br1440.platform.tracing.autoconfigure;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;
import space.br1440.platform.tracing.autoconfigure.jmx.ConfigApplyResult;
import space.br1440.platform.tracing.autoconfigure.jmx.PlatformTracingJmxClient;
import space.br1440.platform.tracing.autoconfigure.jmx.TracingControlDomain;
import space.br1440.platform.tracing.autoconfigure.sampling.SamplingRuntimeConfig;
import space.br1440.platform.tracing.autoconfigure.sampling.ScrubbingRuntimeConfig;
import space.br1440.platform.tracing.autoconfigure.sampling.ValidationRuntimeConfig;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Контракт {@link RuntimeConfigApplier}: one-call-per-domain push, best-effort изоляция
 * ошибок доменов, no-op при недоступных MBean.
 */
class RuntimeConfigApplierTest {

    private PlatformTracingJmxClient client;
    private RuntimeConfigApplier applier;
    private TracingProperties properties;

    @BeforeEach
    void setUp() {
        client = Mockito.mock(PlatformTracingJmxClient.class);
        applier = new RuntimeConfigApplier(client);
        properties = new TracingProperties();
        properties.getDiagnostics().setLogLevel("WARN");
    }

    @Test
    void applyAll_пушит_каждый_домен_ровно_один_раз() {
        when(client.allMBeansAvailable()).thenReturn(true);

        applier.applyAll(properties);

        verify(client, times(1)).updateSamplingPolicy(any(SamplingRuntimeConfig.class));
        verify(client, times(1)).updateScrubbingPolicy(any(ScrubbingRuntimeConfig.class));
        verify(client, times(1)).updateValidationPolicy(any(ValidationRuntimeConfig.class));
        verify(client, times(1)).setExportEnabled(anyBoolean());
        verify(client, times(1)).setPropagationEnabled(anyBoolean());
        verify(client, times(1)).setPlatformLogLevel(eq("WARN"));
    }

    @Test
    void applySampling_один_atomic_updateSamplingPolicy_из_schema_v1() {
        when(client.allMBeansAvailable()).thenReturn(true);
        LinkedHashMap<String, Double> routeRatios = new LinkedHashMap<>();
        routeRatios.put("/api", 0.5d);
        routeRatios.put("/v2", 0.2d);
        properties.getSampling()
                .setEnabled(false)
                .setRatio(0.33d)
                .setDropPaths(List.of("/health"))
                .setForceRecordHeaderValues(List.of("on", "debug"))
                .setRouteRatios(routeRatios);

        applier.applyAll(properties);

        ArgumentCaptor<SamplingRuntimeConfig> captor = ArgumentCaptor.forClass(SamplingRuntimeConfig.class);
        verify(client).updateSamplingPolicy(captor.capture());
        SamplingRuntimeConfig config = captor.getValue();
        assertThat(config.enabled()).isFalse();
        assertThat(config.defaultRatio()).isEqualTo(0.33d);
        assertThat(config.droppedRoutes()).containsExactly("/health");
        assertThat(config.forceRecordValues()).containsExactly("on", "debug");
        assertThat(config.routeRatioPrefixes()).containsExactly("/api", "/v2");
        assertThat(config.routeRatioValues()).containsExactly(0.5d, 0.2d);
    }

    @Test
    void applySampling_не_вызывает_legacy_setters() {
        when(client.allMBeansAvailable()).thenReturn(true);

        applier.applyAll(properties);

        verify(client, never()).setSamplerEnabled(anyBoolean());
        verify(client, never()).setRatio(anyDouble());
        verify(client, never()).setDropPathPrefixes(any());
        verify(client, never()).setForceRecordValues(any());
    }

    @Test
    void applySampling_пустые_routeRatios_и_null_списки() {
        when(client.allMBeansAvailable()).thenReturn(true);
        properties.getSampling()
                .setDropPaths(null)
                .setForceRecordHeaderValues(null)
                .setRouteRatios(new LinkedHashMap<>());

        applier.applyAll(properties);

        ArgumentCaptor<SamplingRuntimeConfig> captor = ArgumentCaptor.forClass(SamplingRuntimeConfig.class);
        verify(client).updateSamplingPolicy(captor.capture());
        SamplingRuntimeConfig config = captor.getValue();
        assertThat(config.droppedRoutes()).isEmpty();
        assertThat(config.forceRecordValues()).isEmpty();
        assertThat(config.routeRatioPrefixes()).isEmpty();
        assertThat(config.routeRatioValues()).isEmpty();
    }

    @Test
    void applyScrubbing_один_atomic_updateScrubbingPolicy_из_schema_v1() {
        when(client.allMBeansAvailable()).thenReturn(true);
        properties.getScrubbing()
                .setEnabled(false)
                .setBuiltInRules(List.of("password", "jwt"));

        applier.applyAll(properties);

        ArgumentCaptor<ScrubbingRuntimeConfig> captor = ArgumentCaptor.forClass(ScrubbingRuntimeConfig.class);
        verify(client).updateScrubbingPolicy(captor.capture());
        ScrubbingRuntimeConfig config = captor.getValue();
        assertThat(config.enabled()).isFalse();
        assertThat(config.ruleNames()).containsExactly("password", "jwt");
    }

    @Test
    void applyScrubbing_не_меняет_sampling_path() {
        when(client.allMBeansAvailable()).thenReturn(true);
        properties.getScrubbing().setEnabled(false);

        applier.applyAll(properties);

        verify(client, times(1)).updateSamplingPolicy(any(SamplingRuntimeConfig.class));
    }

    @Test
    void applyScrubbing_пустой_builtInRules() {
        when(client.allMBeansAvailable()).thenReturn(true);
        properties.getScrubbing().setBuiltInRules(null);

        applier.applyAll(properties);

        ArgumentCaptor<ScrubbingRuntimeConfig> captor = ArgumentCaptor.forClass(ScrubbingRuntimeConfig.class);
        verify(client).updateScrubbingPolicy(captor.capture());
        assertThat(captor.getValue().ruleNames()).isEmpty();
    }

    @Test
    void applyValidation_один_atomic_updateValidationPolicy_из_schema_v1() {
        when(client.allMBeansAvailable()).thenReturn(true);
        properties.getValidation()
                .setEnabled(false)
                .setStrict(true);

        applier.applyAll(properties);

        ArgumentCaptor<ValidationRuntimeConfig> captor = ArgumentCaptor.forClass(ValidationRuntimeConfig.class);
        verify(client).updateValidationPolicy(captor.capture());
        ValidationRuntimeConfig config = captor.getValue();
        assertThat(config.enabled()).isFalse();
        assertThat(config.strict()).isTrue();
    }

    @Test
    void applyValidation_не_вызывает_legacy_2arg_update() {
        when(client.allMBeansAvailable()).thenReturn(true);

        applier.applyAll(properties);

        verify(client, never()).updateValidationPolicy(anyBoolean(), anyBoolean());
    }

    @Test
    void applyValidation_не_меняет_sampling_и_scrubbing_path() {
        when(client.allMBeansAvailable()).thenReturn(true);
        properties.getValidation().setEnabled(false);

        applier.applyAll(properties);

        verify(client, times(1)).updateSamplingPolicy(any(SamplingRuntimeConfig.class));
        verify(client, times(1)).updateScrubbingPolicy(any(ScrubbingRuntimeConfig.class));
    }

    @Test
    void applyAll_noop_если_MBean_недоступен() {
        when(client.allMBeansAvailable()).thenReturn(false);

        applier.applyAll(properties);

        verify(client, never()).updateSamplingPolicy(any(SamplingRuntimeConfig.class));
        verify(client, never()).updateScrubbingPolicy(any(ScrubbingRuntimeConfig.class));
        verify(client, never()).updateValidationPolicy(any(ValidationRuntimeConfig.class));
        verify(client, never()).setExportEnabled(anyBoolean());
    }

    @Test
    void applyAll_изолирует_отказ_одного_домена() {
        when(client.allMBeansAvailable()).thenReturn(true);
        doThrow(new RuntimeException("sampling boom"))
                .when(client).updateSamplingPolicy(any(SamplingRuntimeConfig.class));

        applier.applyAll(properties);

        verify(client, times(1)).updateScrubbingPolicy(any(ScrubbingRuntimeConfig.class));
        verify(client, times(1)).updateValidationPolicy(any(ValidationRuntimeConfig.class));
        verify(client, times(1)).setExportEnabled(anyBoolean());
        verify(client, times(1)).setPlatformLogLevel(eq("WARN"));
    }

    @Test
    void applyAll_изолирует_отказ_validation_домена() {
        when(client.allMBeansAvailable()).thenReturn(true);
        doThrow(new RuntimeException("validation boom"))
                .when(client).updateValidationPolicy(any(ValidationRuntimeConfig.class));

        applier.applyAll(properties);

        verify(client, times(1)).setExportEnabled(anyBoolean());
        verify(client, times(1)).setPlatformLogLevel(eq("WARN"));
    }

    @Test
    void applyAll_пропускает_пустой_logLevel() {
        when(client.allMBeansAvailable()).thenReturn(true);
        properties.getDiagnostics().setLogLevel(null);

        applier.applyAll(properties);

        verify(client, never()).setPlatformLogLevel(any());
    }

    @Test
    void runtimeConfigApplier_не_refresh_scoped() {
        assertThat(RuntimeConfigApplier.class.getAnnotations())
                .noneMatch(a -> "RefreshScope".equals(a.annotationType().getSimpleName()));
    }

    // -- Metric tests: platform.tracing.config.apply.result{domain,result} --

    @Test
    void метрика_success_инкрементируется_при_успешном_push() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RuntimeConfigApplier meteredApplier = new RuntimeConfigApplier(client, registry);
        when(client.allMBeansAvailable()).thenReturn(true);

        meteredApplier.applyAll(properties);

        Counter samplingSuccess = registry.find("platform.tracing.config.apply.result")
                .tag("domain", "sampling")
                .tag("result", "success")
                .counter();
        assertThat(samplingSuccess).isNotNull();
        assertThat(samplingSuccess.count()).isEqualTo(1.0);
    }

    @Test
    void метрика_failure_инкрементируется_при_ошибке_push() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RuntimeConfigApplier meteredApplier = new RuntimeConfigApplier(client, registry);
        when(client.allMBeansAvailable()).thenReturn(true);
        doThrow(new RuntimeException("sampling boom"))
                .when(client).updateSamplingPolicy(any(SamplingRuntimeConfig.class));

        meteredApplier.applyAll(properties);

        Counter samplingFailure = registry.find("platform.tracing.config.apply.result")
                .tag("domain", "sampling")
                .tag("result", "failure")
                .counter();
        assertThat(samplingFailure).isNotNull();
        assertThat(samplingFailure.count()).isEqualTo(1.0);
    }

    @Test
    void метрика_отражает_failure_и_success_при_изоляции_доменов() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RuntimeConfigApplier meteredApplier = new RuntimeConfigApplier(client, registry);
        when(client.allMBeansAvailable()).thenReturn(true);
        doThrow(new RuntimeException("sampling boom"))
                .when(client).updateSamplingPolicy(any(SamplingRuntimeConfig.class));

        meteredApplier.applyAll(properties);

        assertThat(registry.find("platform.tracing.config.apply.result")
                .tag("domain", "sampling").tag("result", "failure").counter())
                .isNotNull()
                .extracting(Counter::count).isEqualTo(1.0);
        assertThat(registry.find("platform.tracing.config.apply.result")
                .tag("domain", "scrubbing").tag("result", "success").counter())
                .isNotNull()
                .extracting(Counter::count).isEqualTo(1.0);
        assertThat(registry.find("platform.tracing.config.apply.result")
                .tag("domain", "validation").tag("result", "success").counter())
                .isNotNull()
                .extracting(Counter::count).isEqualTo(1.0);
    }

    @Test
    void метрика_не_записывается_если_registry_null() {
        when(client.allMBeansAvailable()).thenReturn(true);

        applier.applyAll(properties);

        verify(client, times(1)).updateSamplingPolicy(any(SamplingRuntimeConfig.class));
    }

    @Test
    void имя_метрики_и_теги_точные() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RuntimeConfigApplier meteredApplier = new RuntimeConfigApplier(client, registry);
        when(client.allMBeansAvailable()).thenReturn(true);

        meteredApplier.applyAll(properties);

        assertThat(registry.find("platform.tracing.config.apply.result").counters()).isNotEmpty();
        registry.find("platform.tracing.config.apply.result").counters().forEach(counter -> {
            assertThat(counter.getId().getTag("domain")).isNotNull();
            assertThat(counter.getId().getTag("result")).isNotNull();
        });
    }

    // -- A+ diagnostics: ConfigApplyResult, rejected/partial counters, apply order --

    @Test
    void applyAll_порядок_доменов_sampling_scrubbing_validation_export_propagation_logLevel() {
        when(client.allMBeansAvailable()).thenReturn(true);

        applier.applyAll(properties);

        InOrder order = inOrder(client);
        order.verify(client).updateSamplingPolicy(any(SamplingRuntimeConfig.class));
        order.verify(client).updateScrubbingPolicy(any(ScrubbingRuntimeConfig.class));
        order.verify(client).updateValidationPolicy(any(ValidationRuntimeConfig.class));
        order.verify(client).setExportEnabled(anyBoolean());
        order.verify(client).setPropagationEnabled(anyBoolean());
        order.verify(client).setPlatformLogLevel(anyString());
    }

    @Test
    void applyAll_gate_rejected_инкрементирует_счётчик() {
        when(client.allMBeansAvailable()).thenReturn(false);

        applier.applyAll(properties);
        applier.applyAll(properties);

        assertThat(applier.getRejectedApplyCount()).isEqualTo(2);
        assertThat(applier.getLastConfigApplyResult()).isNull();
    }

    @Test
    void applyAll_полный_успех_lastConfigApplyResult_содержит_точные_множества() {
        when(client.allMBeansAvailable()).thenReturn(true);

        applier.applyAll(properties);

        ConfigApplyResult result = applier.getLastConfigApplyResult();
        assertThat(result).isNotNull();
        assertThat(result.failed()).isEmpty();
        assertThat(result.applied()).contains(
                TracingControlDomain.SAMPLING,
                TracingControlDomain.SCRUBBING,
                TracingControlDomain.VALIDATION,
                TracingControlDomain.EXPORT,
                TracingControlDomain.DIAGNOSTICS);
        assertThat(result.isFullSuccess()).isTrue();
        assertThat(result.isPartial()).isFalse();
        assertThat(result.timestamp()).isNotNull();
    }

    @Test
    void applyAll_partial_lastConfigApplyResult_содержит_точные_applied_и_failed() {
        when(client.allMBeansAvailable()).thenReturn(true);
        doThrow(new RuntimeException("sampling boom"))
                .when(client).updateSamplingPolicy(any(SamplingRuntimeConfig.class));

        applier.applyAll(properties);

        ConfigApplyResult result = applier.getLastConfigApplyResult();
        assertThat(result).isNotNull();
        assertThat(result.applied()).contains(
                TracingControlDomain.SCRUBBING,
                TracingControlDomain.VALIDATION,
                TracingControlDomain.EXPORT,
                TracingControlDomain.DIAGNOSTICS);
        assertThat(result.failed()).containsExactly(TracingControlDomain.SAMPLING);
        assertThat(result.isPartial()).isTrue();
        assertThat(result.isFullSuccess()).isFalse();
    }

    @Test
    void applyAll_partial_инкрементирует_partialApplyCount() {
        when(client.allMBeansAvailable()).thenReturn(true);
        doThrow(new RuntimeException("scrubbing boom"))
                .when(client).updateScrubbingPolicy(any(ScrubbingRuntimeConfig.class));

        applier.applyAll(properties);
        applier.applyAll(properties);

        assertThat(applier.getPartialApplyCount()).isEqualTo(2);
        assertThat(applier.getRejectedApplyCount()).isEqualTo(0);
    }

    @Test
    void applyAll_успех_не_инкрементирует_partialApplyCount() {
        when(client.allMBeansAvailable()).thenReturn(true);

        applier.applyAll(properties);
        applier.applyAll(properties);

        assertThat(applier.getPartialApplyCount()).isEqualTo(0);
    }

    @Test
    void applyAll_lastConfigApplyResult_обновляется_при_каждом_вызове() {
        when(client.allMBeansAvailable()).thenReturn(true);

        applier.applyAll(properties);
        ConfigApplyResult first = applier.getLastConfigApplyResult();

        doThrow(new RuntimeException("second call boom"))
                .when(client).updateSamplingPolicy(any(SamplingRuntimeConfig.class));
        applier.applyAll(properties);
        ConfigApplyResult second = applier.getLastConfigApplyResult();

        assertThat(second).isNotSameAs(first);
        assertThat(second.failed()).containsExactly(TracingControlDomain.SAMPLING);
    }

    @Test
    void applyAll_rejected_lastConfigApplyResult_остаётся_null_при_первом_отклонении() {
        when(client.allMBeansAvailable()).thenReturn(false);

        applier.applyAll(properties);

        assertThat(applier.getLastConfigApplyResult()).isNull();
        assertThat(applier.getRejectedApplyCount()).isEqualTo(1);
    }

    @Test
    void ConfigApplyResult_isFullFailure_когда_все_домены_упали() {
        when(client.allMBeansAvailable()).thenReturn(true);
        doThrow(new RuntimeException("boom")).when(client).updateSamplingPolicy(any());
        doThrow(new RuntimeException("boom")).when(client).updateScrubbingPolicy(any());
        doThrow(new RuntimeException("boom")).when(client).updateValidationPolicy(any());
        doThrow(new RuntimeException("boom")).when(client).setExportEnabled(anyBoolean());
        doThrow(new RuntimeException("boom")).when(client).setPropagationEnabled(anyBoolean());
        doThrow(new RuntimeException("boom")).when(client).setPlatformLogLevel(anyString());

        applier.applyAll(properties);

        ConfigApplyResult result = applier.getLastConfigApplyResult();
        assertThat(result.applied()).isEmpty();
        assertThat(result.isFullFailure()).isTrue();
    }
}
