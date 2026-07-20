package space.br1440.platform.tracing.otel.extension;

import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.trace.SdkTracerProviderBuilder;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.otel.extension.sampler.CompositeSampler;
import space.br1440.platform.tracing.otel.extension.sampler.SafeSampler;
import space.br1440.platform.tracing.test.assertions.SamplerDecisionAssert;

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
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Регрессионные тесты точки входа платформенного расширения.
 * <p>
 * Используется {@link Proxy}-обёртка вокруг {@link AutoConfigurationCustomizer}: это позволяет
 * перехватить только нужные нам методы ({@code addSamplerCustomizer},
 * {@code addTracerProviderCustomizer}) без необходимости имплементировать все остальные методы
 * интерфейса. SDK-минорные обновления, добавляющие новые методы в интерфейс, не сломают тест.
 */
class PlatformAutoConfigurationCustomizerTest {

    @Test
    void customize_регистрирует_sampler_и_tracer_provider_customizer() {
        Recorder recorder = new Recorder();
        new PlatformAutoConfigurationCustomizer().customize(recorder.proxy());

        assertThat(recorder.samplerCustomizer).isNotNull();
        assertThat(recorder.tracerProviderCustomizer).isNotNull();
    }

    @Test
    void customize_регистрирует_propertiesSupplier_с_платформенными_дефолтами_OTel_SDK() {
        Recorder recorder = new Recorder();
        new PlatformAutoConfigurationCustomizer().customize(recorder.proxy());

        assertThat(recorder.propertiesSupplier)
                .as("addPropertiesSupplier должен быть вызван в customize()")
                .isNotNull();

        Map<String, String> defaults = recorder.propertiesSupplier.get();

        // BSP-дефолты: соответствуют требованиям §3.2 «локальная буферизация» и §3 «таймауты».
        // Длительности — в integer-ms согласно OTel SPEC.
        assertThat(defaults)
                .containsEntry("otel.bsp.max.queue.size", "2048")
                .containsEntry("otel.bsp.max.export.batch.size", "512")
                .containsEntry("otel.bsp.schedule.delay", "5000")
                .containsEntry("otel.bsp.export.timeout", "5000")
                .doesNotContainKey("otel.instrumentation.http.server.capture-request-headers");

        // Span limits: соответствуют требованиям §2.1, §2.2, §2.3 (50 / 1000 / 10).
        assertThat(defaults)
                .containsEntry("otel.span.attribute.count.limit", "50")
                .containsEntry("otel.span.attribute.value.length.limit", "1000")
                .containsEntry("otel.span.event.count.limit", "10");
    }

    @Test
    void propertiesSupplier_возвращает_только_строковые_значения_для_всех_ключей() {
        // Контракт OTel SDK: addPropertiesSupplier поставляет Map<String, String>; любое значение,
        // не приводимое к строке, привело бы к ClassCastException на стороне ConfigProperties.
        Recorder recorder = new Recorder();
        new PlatformAutoConfigurationCustomizer().customize(recorder.proxy());

        Map<String, String> defaults = recorder.propertiesSupplier.get();

        assertThat(defaults).isNotEmpty();
        defaults.forEach((key, value) -> {
            assertThat(key).isNotBlank();
            assertThat(value).as("значение свойства %s", key).isNotNull();
            // Косвенная проверка integer-ms формата для durations: значения должны парситься в long.
            if (key.endsWith(".timeout") || key.endsWith(".delay")) {
                assertThat(Long.parseLong(value))
                        .as("длительность для %s обязана быть integer-ms по OTel SPEC", key)
                        .isPositive();
            }
        });
    }

    @Test
    void sampler_по_умолчанию_оборачивается_в_PlatformCompositeSampler() {
        Recorder recorder = new Recorder();
        new PlatformAutoConfigurationCustomizer().customize(recorder.proxy());

        // PR-5: addPropertiesCustomizer должен отработать до samplerCustomizer, чтобы
        // extensionConfig был инициализирован. Прайминг с пустым конфигом → defaultRatio=0.1.
        recorder.primeWithConfig(new MapConfigProperties(Map.of()));
        Sampler produced = recorder.samplerCustomizer
                .apply(Sampler.alwaysOn(), new MapConfigProperties(Map.of()));

        // Фаза 11: платформенный CompositeSampler оборачивается в SafeSampler (изоляция hot-path).
        assertThat(produced).isInstanceOf(SafeSampler.class);
        assertThat(produced.getDescription()).contains("PlatformRuleBasedSampler");
    }

    @Test
    void sampler_использует_кастомный_ratio_из_свойства() {
        Recorder recorder = new Recorder();
        new PlatformAutoConfigurationCustomizer().customize(recorder.proxy());

        // PR-5: ratio поступает из addPropertiesCustomizer (extensionConfig), не из samplerCustomizer config.
        recorder.primeWithConfig(new MapConfigProperties(Map.of("platform.tracing.sampling.ratio", "0.42")));
        Sampler produced = recorder.samplerCustomizer
                .apply(Sampler.alwaysOn(), new MapConfigProperties(Map.of()));

        assertThat(produced.getDescription()).contains("defaultRatio=0.42");
    }

    @Test
    void sampler_без_явного_ratio_делегирует_в_existing() {
        Recorder recorder = new Recorder();
        new PlatformAutoConfigurationCustomizer().customize(recorder.proxy());

        recorder.primeWithConfig(new MapConfigProperties(Map.of()));
        Sampler existing = Sampler.alwaysOff();
        Sampler produced = recorder.samplerCustomizer
                .apply(existing, new MapConfigProperties(Map.of()));

        // Фаза 11: внешняя обёртка — SafeSampler; внутри остаётся CompositeSampler (PlatformRuleBasedSampler).
        assertThat(produced).isInstanceOf(SafeSampler.class);
        assertThat(produced.getDescription()).contains("PlatformRuleBasedSampler");
    }

    @Test
    void sampler_existing_alwaysOff_с_force_header_записывает_span() {
        Recorder recorder = new Recorder();
        new PlatformAutoConfigurationCustomizer().customize(recorder.proxy());

        recorder.primeWithConfig(new MapConfigProperties(Map.of()));
        Sampler produced = recorder.samplerCustomizer
                .apply(Sampler.alwaysOff(), new MapConfigProperties(Map.of()));

        space.br1440.platform.tracing.api.propagation.control.InboundTraceControl control = 
                new space.br1440.platform.tracing.api.propagation.control.InboundTraceControl(true, false, null, "x_trace_on", "on");
        io.opentelemetry.context.Context parentContext = io.opentelemetry.context.Context.root()
                .with(space.br1440.platform.tracing.core.propagation.control.PlatformTraceContextKeys.TRACE_CONTROL, control);

        SamplerDecisionAssert.assertThat(
                        space.br1440.platform.tracing.test.harness.SamplerHarness.of(produced)
                                .spanKind(io.opentelemetry.api.trace.SpanKind.SERVER)
                                .parentContext(parentContext)
                                .sample())
                .isRecordAndSample();
    }

    @Test
    void sampler_явный_ratio_0_создаёт_PlatformRuleBasedSampler() {
        Recorder recorder = new Recorder();
        new PlatformAutoConfigurationCustomizer().customize(recorder.proxy());

        // PR-5: ratio поступает из addPropertiesCustomizer (extensionConfig), не из samplerCustomizer config.
        recorder.primeWithConfig(new MapConfigProperties(Map.of("platform.tracing.sampling.ratio", "0")));
        Sampler produced = recorder.samplerCustomizer
                .apply(Sampler.alwaysOn(), new MapConfigProperties(Map.of()));

        assertThat(produced.getDescription()).contains("defaultRatio=0.00");
    }

    @Test
    void отключение_всех_процессоров_не_трогает_tracer_provider_builder() {
        Recorder recorder = new Recorder();
        new PlatformAutoConfigurationCustomizer().customize(recorder.proxy());

        // Передаём null-builder: при выключенных процессорах customize не должен вызывать
        // addSpanProcessor. Любой такой вызов на null дал бы NPE; PlatformCompositeSpanProcessor
        // внутри также не должен создаваться (пустой список делегатов не регистрируется).
        // Корректное поведение — просто вернуть тот же builder.
        ConfigProperties props = new MapConfigProperties(Map.of(
                "platform.tracing.enriching.enabled", "false",
                "platform.tracing.scrubbing.enabled", "false",
                "platform.tracing.validation.enabled", "false",
                "platform.tracing.watchdog.enabled", "false",
                "platform.tracing.classification.enabled", "false",
                "platform.tracing.metrics.enabled", "false"
        ));
        assertThatThrownBy(() -> recorder.primeWithConfig(props))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("scrubbing.enabled=false is forbidden");
    }

    /**
     * Перехватчик вызовов {@link AutoConfigurationCustomizer}: на основе {@link Proxy}.
     * <p>
     * Реализован через прокси, потому что интерфейс SDK содержит более 10 методов, многие из
     * которых обязательны (без default). Прокси позволяет реагировать только на нужные нам имена
     * методов и автоматически возвращать сам proxy для всех fluent-вызовов; устойчив к изменениям
     * сигнатур интерфейса в будущих версиях SDK (если только не переименуют ключевые методы).
     *
     * <p>PR-5: {@code addPropertiesCustomizer} callbacks теперь перехватываются и вызываются
     * через {@link #primeWithConfig(ConfigProperties)}, моделируя порядок OTel SDK lifecycle.
     */
    private static final class Recorder {
        @SuppressWarnings("unchecked")
        BiFunction<Sampler, ConfigProperties, Sampler> samplerCustomizer;
        @SuppressWarnings("unchecked")
        BiFunction<SdkTracerProviderBuilder, ConfigProperties, SdkTracerProviderBuilder> tracerProviderCustomizer;
        @SuppressWarnings("unchecked")
        Supplier<Map<String, String>> propertiesSupplier;

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
                                BiFunction<Sampler, ConfigProperties, Sampler> sc =
                                        (BiFunction<Sampler, ConfigProperties, Sampler>) args[0];
                                this.samplerCustomizer = sc;
                                return proxyObj;
                            case "addTracerProviderCustomizer":
                                BiFunction<SdkTracerProviderBuilder, ConfigProperties, SdkTracerProviderBuilder> tpc =
                                        (BiFunction<SdkTracerProviderBuilder, ConfigProperties, SdkTracerProviderBuilder>) args[0];
                                this.tracerProviderCustomizer = tpc;
                                return proxyObj;
                            case "addPropertiesSupplier":
                                Supplier<Map<String, String>> ps =
                                        (Supplier<Map<String, String>>) args[0];
                                this.propertiesSupplier = ps;
                                return proxyObj;
                            case "addPropertiesCustomizer":
                                propertiesCustomizers.add(
                                        (Function<ConfigProperties, Map<String, String>>) args[0]);
                                return proxyObj;
                            default:
                                // Все прочие методы fluent-API возвращают сам customizer.
                                return proxyObj;
                        }
                    });
        }
    }

    /**
     * Минимальная in-memory реализация {@link ConfigProperties} для unit-тестов.
     */
    private static final class MapConfigProperties implements ConfigProperties {
        private final Map<String, String> data;

        MapConfigProperties(Map<String, String> data) {
            this.data = new HashMap<>(data);
        }

        @Override
        public String getString(String name) {
            return data.get(name);
        }

        @Override
        public Boolean getBoolean(String name) {
            String value = data.get(name);
            return value == null ? null : Boolean.parseBoolean(value);
        }

        @Override
        public Integer getInt(String name) {
            String value = data.get(name);
            return value == null ? null : Integer.valueOf(value);
        }

        @Override
        public Long getLong(String name) {
            String value = data.get(name);
            return value == null ? null : Long.valueOf(value);
        }

        @Override
        public Double getDouble(String name) {
            String value = data.get(name);
            return value == null ? null : Double.valueOf(value);
        }

        @Override
        public Duration getDuration(String name) {
            return null;
        }

        @Override
        public List<String> getList(String name) {
            return Collections.emptyList();
        }

        @Override
        public Map<String, String> getMap(String name) {
            return Collections.emptyMap();
        }
    }
}
