package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

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
import java.util.stream.Stream;
import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import space.br1440.platform.tracing.otel.javaagent.AutoConfiguredSdkIsolationExtension;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.session.AutoconfigureSessionFactory;
import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.jmx.registration.PlatformTracingMBeanRegistrar;
import space.br1440.platform.tracing.otel.javaagent.sampler.SafeSampler;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.assembly.PlatformScrubberFactory;
import space.br1440.platform.tracing.test.assertions.SamplerDecisionAssert;

import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigurationException;
import io.opentelemetry.sdk.autoconfigure.spi.internal.DefaultConfigProperties;
import io.opentelemetry.sdk.trace.SdkTracerProviderBuilder;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
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
 * перехватить только нужные методы ({@code addSamplerCustomizer}, {@code addTracerProviderCustomizer})
 * без необходимости имплементировать все остальные методы интерфейса.
 * SDK-минорные обновления, добавляющие новые методы в интерфейс, не сломают тест.
 */
@ExtendWith(AutoConfiguredSdkIsolationExtension.class)
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class PlatformAutoConfigurationCustomizerTest {

    private static BootstrapAutoConfigurationCustomizerProvider newProvider() {
        return new BootstrapAutoConfigurationCustomizerProvider(BootstrapCoordinator.createDefault());
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
        assertThat(defaults)
                .containsEntry("otel.bsp.max.queue.size", "2048")
                .containsEntry("otel.bsp.max.export.batch.size", "512")
                .containsEntry("otel.bsp.schedule.delay", "5000")
                .containsEntry("otel.bsp.export.timeout", "5000")
                .doesNotContainKey("otel.instrumentation.http.server.capture-request-headers");

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

        // ratio поступает из addPropertiesCustomizer (extensionConfig), не из samplerCustomizer config.
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
        BootstrapCoordinator coordinator = BootstrapCoordinator.createDefault();
        new BootstrapAutoConfigurationCustomizerProvider(coordinator).customize(recorder.proxy());

        ConfigProperties props = new MapConfigProperties(Map.of(
                "platform.tracing.javaagent.scrubbing.enabled", "false",
                "platform.tracing.javaagent.span-attributes.validation.level", "DISABLED",
                "platform.tracing.span-timeout.enabled", "false",
                "platform.tracing.classification.enabled", "false",
                "platform.tracing.metrics.enabled", "false"
        ));

        try (coordinator) {
            // when
            assertThatThrownBy(() -> recorder.primeWithConfig(props))
                    .isInstanceOf(BootstrapException.class)
                    .satisfies(failure -> assertThat(failure.getCause())
                            .hasMessageContaining("scrubbing.enabled=false is forbidden"));
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidConfigurations")
    void invalid_configuration_publishes_sticky_failed_snapshot(InvalidConfiguration invalid) {
        // given
        MBeanServer server = MBeanServerFactory.newMBeanServer();
        BootstrapCoordinator coordinator = coordinator(server);
        Recorder recorder = new Recorder();
        new BootstrapAutoConfigurationCustomizerProvider(coordinator).customize(recorder.proxy());
        ConfigProperties config = DefaultConfigProperties.createFromMap(invalid.properties());

        try (coordinator) {
            // when
            BootstrapException first = catchThrowableOfType(
                    BootstrapException.class,
                    () -> recorder.primeWithConfig(config)
            );
            BootstrapException repeated = catchThrowableOfType(
                    BootstrapException.class,
                    () -> recorder.primeWithConfig(config)
            );
            BootstrapException exporterFailure = catchThrowableOfType(
                    BootstrapException.class,
                    () -> {
                        try (SpanExporter exporter = recorder.spanExporterCustomizer.apply(null, config)) {
                            assertThat(exporter).isNull();
                        }
                    }
            );
            BootstrapException processorFailure = catchThrowableOfType(
                    BootstrapException.class,
                    () -> {
                        try (SpanProcessor processor = recorder.spanProcessorCustomizer.apply(null, config)) {
                            assertThat(processor).isNull();
                        }
                    }
            );

            // then
            assertThat(first.failure().code()).isEqualTo("CONFIGURATION_INITIALIZATION_FAILED");
            assertThat(first.getCause()).hasMessageContaining(invalid.expectedProperty());
            assertThat(first.failure().cause()).isSameAs(first.getCause());
            assertThat(repeated.failure().code()).isEqualTo(first.failure().code());
            assertThat(repeated.getCause()).isSameAs(first.getCause());
            assertThat(coordinator.state()).isEqualTo(BootstrapState.FAILED);
            assertThat(coordinator.currentWire().lifecycle()).isEqualTo("FAILED");
            assertThat(coordinator.currentWire().failureCode())
                    .isEqualTo("CONFIGURATION_INITIALIZATION_FAILED");
            assertThat(coordinator.currentWire().agentInstanceId()).isNotBlank();
            assertThat(coordinator.currentWire().batchId()).isEmpty();
            assertThat(coordinator.currentWire().evidenceGeneration()).isZero();
            assertThat(coordinator.currentWire().evidence().complete()).isFalse();

            assertThat(exporterFailure.failure().code()).isEqualTo(first.failure().code());
            assertThat(exporterFailure.getCause()).isSameAs(first.getCause());
            assertThat(processorFailure.failure().code()).isEqualTo(first.failure().code());
            assertThat(processorFailure.getCause()).isSameAs(first.getCause());

            List<Throwable> readerFailures = causeChain(first)
                    .stream()
                    .filter(PlatformAutoConfigurationCustomizerTest::originatesFromExtensionConfigReader)
                    .toList();
            if (invalid.parsingFailure()) {
                assertThat(first.getCause()).isInstanceOf(ConfigurationException.class);
                assertThat(first.getCause().getMessage())
                        .contains(invalid.expectedProperty() + "=" + invalid.rawValue());
                assertThat(first.getCause().getStackTrace()[0].getClassName())
                        .isEqualTo(DefaultConfigProperties.class.getName());
                assertThat(readerFailures)
                        .as("parse failure must remain the direct DefaultConfigProperties cause "
                                + "without an ExtensionConfigReader wrapper")
                        .isEmpty();
            } else {
                assertThat(readerFailures)
                        .as("validation failure must originate from ExtensionConfigReader")
                        .isNotEmpty()
                        .allSatisfy(cause -> assertThat(cause.getMessage())
                                .doesNotContain(invalid.rawValue()));
            }
        }
    }

    private static Stream<InvalidConfiguration> invalidConfigurations() {
        return Stream.of(
                invalidDuration("zero span timeout", ExtensionPropertyNames.SPAN_TIMEOUT_MAX_SPAN_AGE, "0"),
                invalidDuration("zero trace timeout", ExtensionPropertyNames.SPAN_TIMEOUT_MAX_TRACE_AGE, "0"),
                invalidDuration("zero scan interval", ExtensionPropertyNames.SPAN_TIMEOUT_SCAN_INTERVAL, "0"),
                invalidInt("zero max tracked traces", ExtensionPropertyNames.SPAN_TIMEOUT_MAX_TRACKED_TRACES),
                invalidInt(
                        "zero max tracked spans per trace",
                        ExtensionPropertyNames.SPAN_TIMEOUT_MAX_TRACKED_SPANS_PER_TRACE
                ),
                invalidInt("zero max tracked spans", ExtensionPropertyNames.SPAN_TIMEOUT_MAX_TRACKED_SPANS),
                new InvalidConfiguration(
                        "trace timeout below span timeout",
                        Map.of(
                                ExtensionPropertyNames.SPAN_TIMEOUT_MAX_SPAN_AGE, "10000",
                                ExtensionPropertyNames.SPAN_TIMEOUT_MAX_TRACE_AGE, "5000"
                        ),
                        ExtensionPropertyNames.SPAN_TIMEOUT_MAX_TRACE_AGE,
                        "5000",
                        false
                ),
                invalidDuration(
                        "zero classification slow threshold",
                        ExtensionPropertyNames.CLASSIFICATION_SLOW_THRESHOLD,
                        "0"
                ),
                invalidDuration(
                        "zero classification normal threshold",
                        ExtensionPropertyNames.CLASSIFICATION_NORMAL_THRESHOLD,
                        "0"
                ),
                new InvalidConfiguration(
                        "unordered classification thresholds",
                        Map.of(
                                ExtensionPropertyNames.CLASSIFICATION_SLOW_THRESHOLD, "1000",
                                ExtensionPropertyNames.CLASSIFICATION_NORMAL_THRESHOLD, "2000"
                        ),
                        ExtensionPropertyNames.CLASSIFICATION_SLOW_THRESHOLD,
                        "1000",
                        false
                ),
                parsingFailure(
                        "non-numeric duration",
                        ExtensionPropertyNames.SPAN_TIMEOUT_SCAN_INTERVAL,
                        "17qx"
                ),
                parsingFailure(
                        "non-numeric integer",
                        ExtensionPropertyNames.SPAN_TIMEOUT_MAX_TRACKED_TRACES,
                        "17qx"
                ),
                invalidDuration(
                        "negative duration",
                        ExtensionPropertyNames.SPAN_TIMEOUT_SCAN_INTERVAL,
                        "-1"
                ),
                parsingFailure(
                        "integer overflow",
                        ExtensionPropertyNames.SPAN_TIMEOUT_MAX_TRACKED_TRACES,
                        "2147483648"
                )
        );
    }

    private static InvalidConfiguration invalidDuration(String description,
                                                        String property,
                                                        String value) {
        return new InvalidConfiguration(description, Map.of(property, value), property, value, false);
    }

    private static InvalidConfiguration invalidInt(String description, String property) {
        return new InvalidConfiguration(description, Map.of(property, "0"), property, "0", false);
    }

    private static InvalidConfiguration parsingFailure(String description,
                                                       String property,
                                                       String value) {
        return new InvalidConfiguration(description, Map.of(property, value), property, value, true);
    }

    private static BootstrapCoordinator coordinator(MBeanServer server) {
        return new BootstrapCoordinator(
                (threadName, action) -> () -> {
                },
                new PlatformScrubberFactory(),
                RuntimeEvidenceTestFixtures.trustedVerifier(),
                AutoconfigureSessionFactory::currentProcessRemoteJmxAssessor,
                new PlatformTracingMBeanRegistrar(server, AgentInstanceId.random())
        );
    }

    private static List<Throwable> causeChain(Throwable failure) {
        List<Throwable> causes = new ArrayList<>();
        Throwable current = failure;
        while (current != null && !causes.contains(current)) {
            causes.add(current);
            current = current.getCause();
        }
        return causes;
    }

    private static boolean originatesFromExtensionConfigReader(Throwable failure) {
        StackTraceElement[] trace = failure.getStackTrace();
        return trace.length > 0
                && "space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionConfigReader"
                .equals(trace[0].getClassName());
    }

    private static final class Recorder {
        BiFunction<Sampler, ConfigProperties, Sampler> samplerCustomizer;
        BiFunction<SdkTracerProviderBuilder, ConfigProperties, SdkTracerProviderBuilder> tracerProviderCustomizer;
        BiFunction<SpanExporter, ConfigProperties, SpanExporter> spanExporterCustomizer;
        BiFunction<SpanProcessor, ConfigProperties, SpanProcessor> spanProcessorCustomizer;
        Supplier<Map<String, String>> propertiesSupplier;

        private final List<Function<ConfigProperties, Map<String, String>>> propertiesCustomizers = new ArrayList<>();

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

                        case "addSpanExporterCustomizer" -> {
                            this.spanExporterCustomizer =
                                    (BiFunction<SpanExporter, ConfigProperties, SpanExporter>) args[0];
                            yield proxyObj;
                        }

                        case "addSpanProcessorCustomizer" -> {
                            this.spanProcessorCustomizer =
                                    (BiFunction<SpanProcessor, ConfigProperties, SpanProcessor>) args[0];
                            yield proxyObj;
                        }

                        default -> proxyObj;
                    });
        }
    }

    private record InvalidConfiguration(String description,
                                        Map<String, String> properties,
                                        String expectedProperty,
                                        String rawValue,
                                        boolean parsingFailure) {

        @Override
        public String toString() {
            return description;
        }
    }

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
