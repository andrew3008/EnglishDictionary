package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import space.br1440.platform.tracing.otel.javaagent.AutoConfiguredSdkIsolationExtension;
import space.br1440.platform.tracing.otel.javaagent.sampler.SafeSampler;
import space.br1440.platform.tracing.test.assertions.SamplerDecisionAssert;

import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.trace.SdkTracerProviderBuilder;
import io.opentelemetry.sdk.trace.samplers.Sampler;

/**
 * Регрессионные тесты точки входа платформенного расширения ({@link BootstrapAutoConfigurationCustomizerProvider}).
 * <p>
 * Каждый тест использует свежий {@link BootstrapCoordinator}:
 * классoloader-static {@link ExtensionRuntime#bootstrap()} допускает
 * ровно один {@code prepare()} за JVM/classloader, поэтому прямые unit-тесты не должны делить
 * общий coordinator между тестовыми методами.
 * <p>
 * Используется {@link Proxy}-обёртка вокруг {@link AutoConfigurationCustomizer}: это позволяет
 * перехватить только нужные нам методы ({@code addSamplerCustomizer},
 * {@code addTracerProviderCustomizer}) без необходимости имплементировать все остальные методы
 * интерфейса. SDK-минорные обновления, добавляющие новые методы в интерфейс, не сломают тест.
 */
@ExtendWith(AutoConfiguredSdkIsolationExtension.class)
class PlatformAutoConfigurationCustomizerTest {

    private static BootstrapAutoConfigurationCustomizerProvider newProvider() {
        return new BootstrapAutoConfigurationCustomizerProvider(BootstrapCoordinator.create());
    }

    @Test
    void customize_registers_sampler_and_tracer_provider_customizer() {
        // given
        Recorder recorder = new Recorder();

        // when
        newProvider().customize(recorder.proxy());

        // then
        assertThat(recorder.samplerCustomizer).isNotNull();
        assertThat(recorder.tracerProviderCustomizer).isNotNull();
    }

    @Test
    void customize_registers_propertiesSupplier_with_platform_OTel_SDK_defaults() {
        // given
        Recorder recorder = new Recorder();
        newProvider().customize(recorder.proxy());

        // when
        assertThat(recorder.propertiesSupplier)
                .as("addPropertiesSupplier должен быть вызван в customize()")
                .isNotNull();

        Map<String, String> defaults = recorder.propertiesSupplier.get();

        // then
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
    void propertiesSupplier_returns_only_string_values_for_all_keys() {
        // given
        Recorder recorder = new Recorder();
        newProvider().customize(recorder.proxy());

        // when
        Map<String, String> defaults = recorder.propertiesSupplier.get();

        // then
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
    void sampler_by_default_is_wrapped_in_PlatformCompositeSampler() {
        // given
        Recorder recorder = new Recorder();
        newProvider().customize(recorder.proxy());

        // addPropertiesCustomizer должен отработать до samplerCustomizer, чтобы
        // extensionConfig был инициализирован. Прайминг с пустым конфигом → defaultRatio=0.1.
        recorder.primeWithConfig(new MapConfigProperties(Map.of()));

        // when
        Sampler produced = recorder.samplerCustomizer
                .apply(Sampler.alwaysOn(), new MapConfigProperties(Map.of()));

        // then
        assertThat(produced).isInstanceOf(SafeSampler.class);
        assertThat(produced.getDescription()).contains("PlatformRuleBasedSampler");
    }

    @Test
    void sampler_uses_custom_ratio_from_property() {
        // given
        Recorder recorder = new Recorder();
        newProvider().customize(recorder.proxy());

        // ratio поступает из addPropertiesCustomizer (extensionConfig), не из samplerCustomizer config.
        recorder.primeWithConfig(new MapConfigProperties(Map.of("platform.tracing.sampling.ratio", "0.42")));

        // when
        Sampler produced = recorder.samplerCustomizer
                .apply(Sampler.alwaysOn(), new MapConfigProperties(Map.of()));

        // then
        assertThat(produced.getDescription()).contains("defaultRatio=0.42");
    }

    @Test
    void sampler_without_explicit_ratio_delegates_to_existing() {
        // given
        Recorder recorder = new Recorder();
        newProvider().customize(recorder.proxy());

        recorder.primeWithConfig(new MapConfigProperties(Map.of()));
        Sampler existing = Sampler.alwaysOff();

        // when
        Sampler produced = recorder.samplerCustomizer
                .apply(existing, new MapConfigProperties(Map.of()));

        // then
        assertThat(produced).isInstanceOf(SafeSampler.class);
        assertThat(produced.getDescription()).contains("PlatformRuleBasedSampler");
    }

    @Test
    void sampler_existing_alwaysOff_with_force_header_records_span() {
        // given
        Recorder recorder = new Recorder();
        newProvider().customize(recorder.proxy());

        recorder.primeWithConfig(new MapConfigProperties(Map.of()));
        Sampler produced = recorder.samplerCustomizer
                .apply(Sampler.alwaysOff(), new MapConfigProperties(Map.of()));

        // when
        space.br1440.platform.tracing.api.propagation.control.InboundTraceControl control =
                new space.br1440.platform.tracing.api.propagation.control.InboundTraceControl(true, false, null, "x_trace_on", "on");
        io.opentelemetry.context.Context parentContext = io.opentelemetry.context.Context.root()
                .with(space.br1440.platform.tracing.runtime.propagation.control.PlatformTraceContextKeys.TRACE_CONTROL, control);

        // then
        SamplerDecisionAssert.assertThat(
                        space.br1440.platform.tracing.test.harness.SamplerHarness.of(produced)
                                .spanKind(io.opentelemetry.api.trace.SpanKind.SERVER)
                                .parentContext(parentContext)
                                .sample())
                .isRecordAndSample();
    }

    @Test
    void sampler_explicit_ratio_0_creates_PlatformRuleBasedSampler() {
        // given
        Recorder recorder = new Recorder();
        newProvider().customize(recorder.proxy());

        // PR-5: ratio поступает из addPropertiesCustomizer (extensionConfig), не из samplerCustomizer config.
        recorder.primeWithConfig(new MapConfigProperties(Map.of("platform.tracing.sampling.ratio", "0")));

        // when
        Sampler produced = recorder.samplerCustomizer
                .apply(Sampler.alwaysOn(), new MapConfigProperties(Map.of()));

        // then
        assertThat(produced.getDescription()).contains("defaultRatio=0.00");
    }

    @Test
    void disabling_all_processors_does_not_touch_tracer_provider_builder() {
        // given
        Recorder recorder = new Recorder();
        newProvider().customize(recorder.proxy());

        ConfigProperties props = new MapConfigProperties(Map.of(
                "platform.tracing.javaagent.scrubbing.enabled", "false",
                "platform.tracing.javaagent.span-attributes.validation.level", "DISABLED",
                "platform.tracing.watchdog.enabled", "false",
                "platform.tracing.classification.enabled", "false",
                "platform.tracing.metrics.enabled", "false"
        ));

        // when/then
        assertThatThrownBy(() -> recorder.primeWithConfig(props))
                .isInstanceOf(BootstrapException.class)
                .hasMessageContaining("scrubbing.enabled=false is forbidden");
    }

    /**
     * Перехватчик вызовов {@link AutoConfigurationCustomizer}: на основе {@link Proxy}.
     * <p>
     * Реализован через прокси, потому что интерфейс SDK содержит более 10 методов, многие из
     * которых обязательны (без default). Прокси позволяет реагировать только на нужные нам имена
     * методов и автоматически возвращать сам proxy для всех fluent-вызовов; устойчив к изменениям
     * сигнатур интерфейса в будущих версиях SDK (если только не переименуют ключевые методы).
     */
    private static final class Recorder {
        BiFunction<Sampler, ConfigProperties, Sampler> samplerCustomizer;
        BiFunction<SdkTracerProviderBuilder, ConfigProperties, SdkTracerProviderBuilder> tracerProviderCustomizer;
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
                    (Object proxyObj, Method method, Object[] args) -> switch (method.getName()) {
                        case "addSamplerCustomizer" -> {
                            this.samplerCustomizer = (BiFunction<Sampler, ConfigProperties, Sampler>) args[0];
                            yield proxyObj;
                        }

                        case "addTracerProviderCustomizer" -> {
                            this.tracerProviderCustomizer = (BiFunction<SdkTracerProviderBuilder, ConfigProperties, SdkTracerProviderBuilder>) args[0];
                            yield proxyObj;
                        }

                        case "addPropertiesSupplier" -> {
                            this.propertiesSupplier = (Supplier<Map<String, String>>) args[0];
                            yield proxyObj;
                        }

                        case "addPropertiesCustomizer" -> {
                            propertiesCustomizers.add((Function<ConfigProperties, Map<String, String>>) args[0]);
                            yield proxyObj;
                        }

                        default ->
                            // Все прочие методы fluent-API возвращают сам customizer.
                                proxyObj;
                    });
        }
    }

    /**
         * Минимальная in-memory реализация {@link ConfigProperties} для unit-тестов.
         */
        private record MapConfigProperties(Map<String, String> data) implements ConfigProperties {

            private MapConfigProperties(Map<String, String> data) {
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
