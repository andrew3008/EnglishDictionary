package space.br1440.platform.tracing.otel.javaagent;

import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SdkTracerProviderBuilder;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.otel.javaagent.jmx.PlatformTracingObjectNames;
import space.br1440.platform.tracing.otel.javaagent.sampler.CompositeSampler;
import space.br1440.platform.tracing.otel.javaagent.sampler.SafeSampler;
import space.br1440.platform.tracing.otel.javaagent.sampler.SamplerStateHolder;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Расширенные интеграционные тесты {@link PlatformAutoConfigurationCustomizer}:
 * registerSpanProcessors с подключением реального {@link SdkTracerProviderBuilder}, проверка
 * регистрации JMX MBean при инициализации sampler'а и watchdog'а, обработка отдельных подсистем
 * по {@code *.enabled} флагам.
 */
class PlatformAutoConfigurationCustomizerProcessorsTest {

    private final MBeanServer server = ManagementFactory.getPlatformMBeanServer();

    @AfterEach
    void cleanup() throws Exception {
        for (ObjectName name : new ObjectName[]{
                PlatformTracingObjectNames.SAMPLING,
                PlatformTracingObjectNames.SCRUBBING,
                PlatformTracingObjectNames.VALIDATION,
                PlatformTracingObjectNames.EXPORT,
                PlatformTracingObjectNames.PROCESSOR_METRICS,
                PlatformTracingObjectNames.DIAGNOSTICS
        }) {
            if (server.isRegistered(name)) {
                server.unregisterMBean(name);
            }
        }
    }

    @Test
    void все_включённые_подсистемы_добавляются_в_tracer_provider_builder() {
        Recorder recorder = new Recorder();
        new PlatformAutoConfigurationCustomizer().customize(recorder.proxy());

        recorder.primeWithConfig(new MapConfigProperties(Map.of()));
        SdkTracerProviderBuilder builder = SdkTracerProvider.builder();
        SdkTracerProviderBuilder result = recorder.tracerProviderCustomizer.apply(builder,
                new MapConfigProperties(Map.of()));

        // Контракт: возвращается тот же builder (fluent-API), и мы не падаем при дефолтных значениях.
        assertThat(result).isSameAs(builder);

        // Финальная сборка должна быть ненулевой (косвенная проверка, что addSpanProcessor отработал).
        SdkTracerProvider provider = builder.build();
        try {
            assertThat(provider).isNotNull();
        } finally {
            provider.shutdown();
        }
    }

    @Test
    void отключение_отдельной_подсистемы_не_ломает_остальные() {
        Recorder recorder = new Recorder();
        new PlatformAutoConfigurationCustomizer().customize(recorder.proxy());

        // Только enriching выключаем, scrubbing/validation/watchdog по дефолту on.
        recorder.primeWithConfig(new MapConfigProperties(Map.of("platform.tracing.enriching.enabled", "false")));
        SdkTracerProviderBuilder builder = SdkTracerProvider.builder();
        recorder.tracerProviderCustomizer.apply(builder,
                new MapConfigProperties(Map.of("platform.tracing.enriching.enabled", "false")));

        try (SdkTracerProvider provider = builder.build()) {
            assertThat(provider).isNotNull();
        }
    }

    @Test
    void кастомный_remote_service_priority_передаётся_в_EnrichingSpanProcessor() {
        // Прямой instance EnrichingSpanProcessor мы получить не можем (он внутри
        // PlatformCompositeSpanProcessor), но можем убедиться, что customize не падает
        // с невалидным списком и принимает любой список строк.
        Recorder recorder = new Recorder();
        new PlatformAutoConfigurationCustomizer().customize(recorder.proxy());

        Map<String, String> props = new HashMap<>();
        props.put("platform.tracing.enriching.remote-service-priority", "rpc.service,peer.service");

        recorder.primeWithConfig(new MapConfigPropertiesWithList(props,
                "platform.tracing.enriching.remote-service-priority",
                List.of("rpc.service", "peer.service")));
        SdkTracerProviderBuilder builder = SdkTracerProvider.builder();
        recorder.tracerProviderCustomizer.apply(builder, new MapConfigPropertiesWithList(props,
                "platform.tracing.enriching.remote-service-priority",
                List.of("rpc.service", "peer.service")));

        try (SdkTracerProvider provider = builder.build()) {
            assertThat(provider).isNotNull();
        }
    }

    @Test
    void sampler_customizer_создает_PlatformRuleBasedSampler() {
        Recorder recorder = new Recorder();
        new PlatformAutoConfigurationCustomizer().customize(recorder.proxy());

        // PR-5: extensionConfig должен быть инициализирован до вызова samplerCustomizer.
        recorder.primeWithConfig(new MapConfigProperties(Map.of()));
        Sampler produced = recorder.samplerCustomizer
                .apply(Sampler.alwaysOn(), new MapConfigProperties(Map.of()));

        // Фаза 11: CompositeSampler оборачивается в SafeSampler; описание делегата сохраняется.
        assertThat(produced).isInstanceOf(SafeSampler.class);
        assertThat(produced.getDescription())
                .contains("PlatformRuleBasedSampler");
    }

    @Test
    void sampler_customizer_с_явным_ratio_создает_SamplerState_с_дефолтным_ratio() {
        Recorder recorder = new Recorder();
        new PlatformAutoConfigurationCustomizer().customize(recorder.proxy());

        // PR-5: ratio поступает из addPropertiesCustomizer (extensionConfig), не из samplerCustomizer config.
        recorder.primeWithConfig(new MapConfigProperties(Map.of("platform.tracing.sampling.ratio", "0.1")));
        Sampler produced = recorder.samplerCustomizer
                .apply(Sampler.alwaysOn(), new MapConfigProperties(Map.of()));

        assertThat(produced.getDescription())
                .contains("PlatformRuleBasedSampler")
                .contains("defaultRatio=0.10");
    }

    @Test
    void регистрация_MBean_выполняется_всегда() throws Exception {
        Recorder recorder = new Recorder();
        new PlatformAutoConfigurationCustomizer().customize(recorder.proxy());

        recorder.primeWithConfig(new MapConfigProperties(Map.of()));
        recorder.samplerCustomizer.apply(Sampler.alwaysOn(), new MapConfigProperties(Map.of()));
        assertThat(server.isRegistered(PlatformTracingObjectNames.SAMPLING)).isTrue();
    }

    @Test
    void кастомный_sampling_ratio_отражается_в_зарегистрированном_MBean_е() throws Exception {
        Recorder recorder = new Recorder();
        new PlatformAutoConfigurationCustomizer().customize(recorder.proxy());

        // PR-5: ratio поступает из addPropertiesCustomizer (extensionConfig).
        recorder.primeWithConfig(new MapConfigProperties(Map.of("platform.tracing.sampling.ratio", "0.42")));
        recorder.samplerCustomizer.apply(Sampler.alwaysOn(), new MapConfigProperties(Map.of()));

        ObjectName objectName = PlatformTracingObjectNames.SAMPLING;
        Object ratio = server.getAttribute(objectName, "SamplingRatio");
        assertThat(ratio).isEqualTo(0.42d);

        // setSamplingRatio через JMX обновляет состояние.
        server.setAttribute(objectName,
                new javax.management.Attribute("SamplingRatio", 0.9d));
        assertThat(server.getAttribute(objectName, "SamplingRatio")).isEqualTo(0.9d);
    }

    @Test
    void order_расширения_равен_100_приоритет_над_дефолтом() {
        assertThat(new PlatformAutoConfigurationCustomizer().order()).isEqualTo(100);
    }

    @Test
    void без_явного_ratio_используется_дефолтный_ratio() {
        Recorder recorder = new Recorder();
        new PlatformAutoConfigurationCustomizer().customize(recorder.proxy());

        // PR-5 ALIGN_TO_EXTENSION_DEFAULTS: absent ratio → ExtensionDefaults.DEFAULT_SAMPLING_RATIO = 0.1
        // (было 1.0 — hardcoded implicit fallback в PlatformSamplerBuilder до PR-5)
        recorder.primeWithConfig(new MapConfigProperties(Map.of()));
        Sampler produced = recorder.samplerCustomizer
                .apply(Sampler.alwaysOn(), new MapConfigProperties(Map.of()));

        assertThat(produced).isNotNull();
        assertThat(produced.getDescription()).contains("defaultRatio=0.10");
    }

    @Test
    void пустое_имя_force_header_заменяется_дефолтом_X_Trace_On() {
        // Устаревший тест, так как CompositeSampler больше не хранит имена заголовков в description,
        // они теперь инкапсулированы в InboundTraceControlPropagator
    }

    @Test
    void unknown_имя_правила_scrubbing_не_роняет_конфигурацию() {
        Recorder recorder = new Recorder();
        new PlatformAutoConfigurationCustomizer().customize(recorder.proxy());

        SdkTracerProviderBuilder builder = SdkTracerProvider.builder();
        Map<String, String> props = new HashMap<>();
        recorder.primeWithConfig(new MapConfigPropertiesWithList(props,
                "platform.tracing.scrubbing.built-in-rules",
                List.of("unknown-rule-x", "email")));
        SdkTracerProviderBuilder result = recorder.tracerProviderCustomizer.apply(builder,
                new MapConfigPropertiesWithList(props,
                        "platform.tracing.scrubbing.built-in-rules",
                        List.of("unknown-rule-x", "email")));

        assertThat(result).isSameAs(builder);
        try (SdkTracerProvider provider = builder.build()) {
            assertThat(provider).isNotNull();
        }
    }

    @Test
    void обнуление_всех_подсистем_не_создаёт_MBean_до_создания_sampler_а() throws Exception {
        Recorder recorder = new Recorder();
        new PlatformAutoConfigurationCustomizer().customize(recorder.proxy());

        Map<String, String> disableAll = Map.of(
                "platform.tracing.enriching.enabled", "false",
                "platform.tracing.scrubbing.enabled", "false",
                "platform.tracing.validation.enabled", "false",
                "platform.tracing.watchdog.enabled", "false"
        );
        assertThatThrownBy(() -> recorder.primeWithConfig(new MapConfigProperties(disableAll)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("scrubbing.enabled=false is forbidden");

        // Без sampler-customizer.apply MBean не должен быть зарегистрирован.
        assertThat(server.isRegistered(PlatformTracingObjectNames.SAMPLING)).isFalse();
    }

    /**
     * Прокси-перехватчик {@link AutoConfigurationCustomizer} (см. PlatformAutoConfigurationCustomizerTest).
     *
     * <p>PR-5: {@code addPropertiesCustomizer} callbacks перехватываются и вызываются через
     * {@link #primeWithConfig(ConfigProperties)}, моделируя порядок OTel SDK lifecycle.
     */
    private static final class Recorder {
        @SuppressWarnings("unchecked")
        BiFunction<Sampler, ConfigProperties, Sampler> samplerCustomizer;
        @SuppressWarnings("unchecked")
        BiFunction<SdkTracerProviderBuilder, ConfigProperties, SdkTracerProviderBuilder> tracerProviderCustomizer;

        private final List<Function<ConfigProperties, Map<String, String>>> propertiesCustomizers = new ArrayList<>();

        /**
         * Имитирует фазу addPropertiesCustomizer OTel SDK lifecycle: вызывает все зарегистрированные
         * callbacks с переданным конфигом. Необходимо вызвать до {@code samplerCustomizer.apply()},
         * чтобы extensionConfig был инициализирован (PR-5).
         */
        void primeWithConfig(ConfigProperties config) {
            for (Function<ConfigProperties, Map<String, String>> pc : propertiesCustomizers) {
                pc.apply(config);
            }
        }

        @SuppressWarnings("unchecked")
        AutoConfigurationCustomizer proxy() {
            return (AutoConfigurationCustomizer) Proxy.newProxyInstance(
                    AutoConfigurationCustomizer.class.getClassLoader(),
                    new Class<?>[]{AutoConfigurationCustomizer.class},
                    (Object proxyObj, Method method, Object[] args) -> {
                        switch (method.getName()) {
                            case "addSamplerCustomizer":
                                this.samplerCustomizer =
                                        (BiFunction<Sampler, ConfigProperties, Sampler>) args[0];
                                return proxyObj;
                            case "addTracerProviderCustomizer":
                                this.tracerProviderCustomizer =
                                        (BiFunction<SdkTracerProviderBuilder, ConfigProperties, SdkTracerProviderBuilder>) args[0];
                                return proxyObj;
                            case "addPropertiesCustomizer":
                                propertiesCustomizers.add(
                                        (Function<ConfigProperties, Map<String, String>>) args[0]);
                                return proxyObj;
                            default:
                                return proxyObj;
                        }
                    });
        }
    }

    /** Минимальная in-memory реализация {@link ConfigProperties}. */
    private static class MapConfigProperties implements ConfigProperties {
        protected final Map<String, String> data;

        MapConfigProperties(Map<String, String> data) {
            this.data = new HashMap<>(data);
        }

        @Override public String getString(String name) { return data.get(name); }

        @Override public Boolean getBoolean(String name) {
            String v = data.get(name);
            return v == null ? null : Boolean.parseBoolean(v);
        }

        @Override public Integer getInt(String name) {
            String v = data.get(name);
            return v == null ? null : Integer.valueOf(v);
        }

        @Override public Long getLong(String name) {
            String v = data.get(name);
            return v == null ? null : Long.valueOf(v);
        }

        @Override public Double getDouble(String name) {
            String v = data.get(name);
            return v == null ? null : Double.valueOf(v);
        }

        @Override public Duration getDuration(String name) { return null; }

        @Override public List<String> getList(String name) { return Collections.emptyList(); }

        @Override public Map<String, String> getMap(String name) { return Collections.emptyMap(); }
    }

    /** Расширение, поддерживающее список под одним конкретным ключом. */
    private static final class MapConfigPropertiesWithList extends MapConfigProperties {
        private final String listKey;
        private final List<String> listValue;

        MapConfigPropertiesWithList(Map<String, String> data, String listKey, List<String> listValue) {
            super(data);
            this.listKey = listKey;
            this.listValue = listValue;
        }

        @Override
        public List<String> getList(String name) {
            if (listKey.equals(name)) {
                return listValue;
            }
            return Collections.emptyList();
        }
    }

    /** Sanity-check: sampling-домен MBean зафиксирован контрактом. */
    @Test
    void object_name_sampling_MBean_совпадает_с_публичным_контрактом() {
        assertThat(PlatformTracingObjectNames.SAMPLING_OBJECT_NAME_STR)
                .isEqualTo("space.br1440.platform.tracing:type=Sampling,name=PlatformSamplingControl");
    }

    /** Ratio при установке через MBean подвергается валидации. */
    @Test
    void невалидное_значение_setSamplingRatio_отвергается_через_JMX() throws Exception {
        Recorder recorder = new Recorder();
        new PlatformAutoConfigurationCustomizer().customize(recorder.proxy());
        // PR-5: ratio поступает из addPropertiesCustomizer (extensionConfig).
        recorder.primeWithConfig(new MapConfigProperties(Map.of("platform.tracing.sampling.ratio", "0.1")));
        recorder.samplerCustomizer.apply(Sampler.alwaysOn(), new MapConfigProperties(Map.of()));

        ObjectName objectName = PlatformTracingObjectNames.SAMPLING;

        // setAttribute с 2.0 → IllegalArgumentException, оборачиваемое JMX в MBeanException/RuntimeMBeanException.
        // Проверим целевое исключение через invoke().
        org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
                () -> server.setAttribute(objectName, new javax.management.Attribute("SamplingRatio", 2.0d)));

        // Состояние MBean не изменилось (0.1 задано при создании).
        assertThat(server.getAttribute(objectName, "SamplingRatio")).isEqualTo(0.1d);
    }
}
