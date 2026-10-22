package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
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

import space.br1440.platform.tracing.api.propagation.control.InboundTraceControl;
import space.br1440.platform.tracing.otel.javaagent.AutoConfiguredSdkIsolationExtension;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.session.AutoconfigureSessionFactory;
import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames;
import space.br1440.platform.tracing.otel.javaagent.configuration.OtelSdkDefaults;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.jmx.registration.PlatformTracingMBeanRegistrar;
import space.br1440.platform.tracing.otel.javaagent.sampler.SafeSampler;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.assembly.PlatformScrubberFactory;
import space.br1440.platform.tracing.runtime.propagation.control.PlatformTraceContextKeys;
import space.br1440.platform.tracing.test.assertions.SamplerDecisionAssert;
import space.br1440.platform.tracing.test.harness.SamplerHarness;

import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigurationException;
import io.opentelemetry.sdk.autoconfigure.spi.internal.DefaultConfigProperties;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SdkTracerProviderBuilder;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;

@ExtendWith(AutoConfiguredSdkIsolationExtension.class)
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class PlatformAutoConfigurationCustomizerTest {

    private static final String HTTP_SENDER_PREFERENCE = "io.opentelemetry.sdk.common.export.HttpSenderProvider";
    private static final String GRPC_SENDER_PREFERENCE = "io.opentelemetry.sdk.common.export.GrpcSenderProvider";

    private static BootstrapAutoConfigurationCustomizerProvider provider(BootstrapCoordinator coordinator) {
        return new BootstrapAutoConfigurationCustomizerProvider(coordinator);
    }

    @Test
    void customize_registers_resource_and_tracer_provider_customizers_without_sampler_customizer() {
        // given
        BootstrapCoordinator coordinator = coordinator();
        Recorder recorder = new Recorder();

        try (coordinator) {
            // when
            provider(coordinator).customize(recorder.proxy());

            // then
            assertThat(recorder.samplerCustomizerRegistrations).isZero();
            assertThat(recorder.resourceCustomizer).isNotNull();
            assertThat(recorder.tracerProviderCustomizer).isNotNull();
        }
    }

    @Test
    void customize_registers_propertiesSupplier_with_platform_OTel_SDK_defaults() {
        // given
        BootstrapCoordinator coordinator = coordinator();
        Recorder recorder = new Recorder();

        try (coordinator) {
            provider(coordinator).customize(recorder.proxy());

            // when
            assertThat(recorder.propertiesSupplier)
                    .as("addPropertiesSupplier должен быть вызван в customize()")
                    .isNotNull();

            Map<String, String> defaults = recorder.propertiesSupplier.get();

            // then
            assertThat(defaults)
                    .doesNotContainKey("otel.traces.sampler")
                    .containsEntry("otel.bsp.max.queue.size", "2048")
                    .containsEntry("otel.bsp.max.export.batch.size", "512")
                    .containsEntry("otel.bsp.schedule.delay", "5000")
                    .containsEntry("otel.bsp.export.timeout",
                            String.valueOf(OtelSdkDefaults.DEFAULT_BSP_EXPORT_TIMEOUT.toMillis()))
                    .doesNotContainKey("otel.instrumentation.http.server.capture-request-headers");

            assertThat(defaults)
                    .containsEntry("otel.span.attribute.count.limit", "50")
                    .containsEntry("otel.span.attribute.value.length.limit", "1000")
                    .containsEntry("otel.span.event.count.limit", "10");
        }
    }

    @Test
    void propertiesSupplier_returns_only_string_values_for_all_keys() {
        // given
        BootstrapCoordinator coordinator = coordinator();
        Recorder recorder = new Recorder();

        try (coordinator) {
            provider(coordinator).customize(recorder.proxy());

            // when
            Map<String, String> defaults = recorder.propertiesSupplier.get();

            // then
            assertThat(defaults).isNotEmpty();
            defaults.forEach((key, value) -> {
                assertThat(key).isNotBlank();
                assertThat(value).as("значение свойства %s", key).isNotNull();
                if (key.endsWith(".timeout") || key.endsWith(".delay")) {
                    assertThat(Long.parseLong(value))
                            .as("длительность для %s обязана быть integer-ms по OTel SPEC", key)
                            .isPositive();
                }
            });
        }
    }

    @Test
    void normalized_sampler_is_visible_to_later_properties_and_resource_customizers() {
        // given
        BootstrapCoordinator coordinator = coordinator();
        Recorder recorder = new Recorder();

        try (coordinator) {
            provider(coordinator).customize(recorder.proxy());

            // when
            ConfigProperties finalConfig = recorder.primeWithConfig(Map.of());

            // then
            assertThat(recorder.propertiesCustomizerInputs).hasSize(2);
            assertThat(recorder.propertiesCustomizerInputs.get(0).getString("otel.traces.sampler")).isNull();
            assertThat(recorder.propertiesCustomizerInputs.get(1).getString("otel.traces.sampler"))
                    .isEqualTo("always_off");
            assertThat(recorder.resourceCustomizerInputs).containsExactly(finalConfig);
            assertThat(finalConfig.getString("otel.traces.sampler")).isEqualTo("always_off");
        }
    }

    @Test
    void session_sampler_is_installed_on_tracer_provider_builder() {
        // given
        BootstrapCoordinator coordinator = coordinator();
        Recorder recorder = new Recorder();

        try (coordinator) {
            provider(coordinator).customize(recorder.proxy());
            ConfigProperties finalConfig = recorder.primeWithConfig(Map.of());
            SdkTracerProviderBuilder builder = SdkTracerProvider.builder();

            // when
            SdkTracerProviderBuilder customized = recorder.tracerProviderCustomizer.apply(builder, finalConfig);

            try (SdkTracerProvider tracerProvider = customized.build()) {
                // then
                assertThat(customized).isSameAs(builder);
                assertThat(tracerProvider.getSampler()).isSameAs(coordinator.session().sampler());
                assertThat(coordinator.session().sampler()).isInstanceOf(SafeSampler.class);
            }
        }
    }

    @Test
    void session_sampler_uses_custom_ratio_from_property() {
        // given
        BootstrapCoordinator coordinator = coordinator();
        Recorder recorder = new Recorder();

        try (coordinator) {
            provider(coordinator).customize(recorder.proxy());

            // when
            recorder.primeWithConfig(Map.of("platform.tracing.sampling.ratio", "0.42"));
            double actualRatio = coordinator.session().policy().current().samplingRatio();

            // then
            assertThat(actualRatio).isEqualTo(0.42d);
        }
    }

    @Test
    void sampler_with_zero_ratio_and_force_header_records_span() {
        // given
        BootstrapCoordinator coordinator = coordinator();
        Recorder recorder = new Recorder();

        try (coordinator) {
            provider(coordinator).customize(recorder.proxy());
            recorder.primeWithConfig(Map.of("platform.tracing.sampling.ratio", "0"));
            SafeSampler sampler = coordinator.session().sampler();

            // when
            InboundTraceControl control = new InboundTraceControl(true, false, null, "x_trace_on", "on");
            Context parentContext = Context.root().with(PlatformTraceContextKeys.TRACE_CONTROL, control);

            // then
            SamplerDecisionAssert.assertThat(
                            SamplerHarness.of(sampler)
                                    .spanKind(SpanKind.SERVER)
                                    .parentContext(parentContext)
                                    .sample())
                    .isRecordAndSample();
        }
    }

    @Test
    void session_sampler_with_explicit_zero_ratio_drops_without_override() {
        // given
        BootstrapCoordinator coordinator = coordinator();
        Recorder recorder = new Recorder();

        try (coordinator) {
            provider(coordinator).customize(recorder.proxy());

            // when
            recorder.primeWithConfig(Map.of("platform.tracing.sampling.ratio", "0"));
            SafeSampler sampler = coordinator.session().sampler();

            // then
            assertThat(coordinator.session().policy().current().samplingRatio()).isZero();
            SamplerDecisionAssert.assertThat(
                            SamplerHarness.of(sampler).sample())
                    .isDrop();
        }
    }

    @Test
    void disabling_scrubbing_is_rejected_by_final_configuration() {
        // given
        Recorder recorder = new Recorder();
        BootstrapCoordinator coordinator = coordinator();
        provider(coordinator).customize(recorder.proxy());

        Map<String, String> properties = Map.of(
                "platform.tracing.javaagent.scrubbing.enabled", "false",
                "platform.tracing.javaagent.span-attributes.validation.level", "DISABLED",
                "platform.tracing.span-timeout.enabled", "false",
                "platform.tracing.classification.enabled", "false",
                "platform.tracing.metrics.enabled", "false"
        );

        try (coordinator) {
            // when
            assertThatThrownBy(() -> recorder.primeWithConfig(properties))
                    .isInstanceOf(BootstrapException.class)
                    .satisfies(failure -> assertThat(failure.getCause())
                            .hasMessageContaining("scrubbing.enabled=false is forbidden"));
        }
    }

    @Test
    void invalid_final_configuration_does_not_install_sender_preferences() {
        // given
        String previousHttp = System.getProperty(HTTP_SENDER_PREFERENCE);
        String previousGrpc = System.getProperty(GRPC_SENDER_PREFERENCE);
        System.clearProperty(HTTP_SENDER_PREFERENCE);
        System.clearProperty(GRPC_SENDER_PREFERENCE);

        try {
            BootstrapCoordinator coordinator = coordinator();
            Recorder recorder = new Recorder();
            provider(coordinator).customize(recorder.proxy());

            try (coordinator) {
                // when
                assertThatThrownBy(() -> recorder.primeWithConfig(Map.of(
                        "otel.traces.sampler", "always_on"
                ))).isInstanceOf(BootstrapException.class);

                // then
                assertThat(System.getProperty(HTTP_SENDER_PREFERENCE)).isNull();
                assertThat(System.getProperty(GRPC_SENDER_PREFERENCE)).isNull();
            }
        } finally {
            restoreSystemProperty(HTTP_SENDER_PREFERENCE, previousHttp);
            restoreSystemProperty(GRPC_SENDER_PREFERENCE, previousGrpc);
        }
    }

    @Test
    void resource_customization_failure_does_not_install_sender_preferences() {
        // given
        String previousHttp = System.getProperty(HTTP_SENDER_PREFERENCE);
        String previousGrpc = System.getProperty(GRPC_SENDER_PREFERENCE);
        System.clearProperty(HTTP_SENDER_PREFERENCE);
        System.clearProperty(GRPC_SENDER_PREFERENCE);

        try {
            BootstrapCoordinator coordinator = coordinator();
            Recorder recorder = new Recorder();
            provider(coordinator).customize(recorder.proxy());

            try (coordinator) {
                // when
                BootstrapException failure = catchThrowableOfType(
                        BootstrapException.class,
                        () -> recorder.primeWithConfig(Map.of(
                                ExtensionPropertyNames.RESOURCE_VALIDATION_MODE,
                                "ERROR"
                        ))
                );

                // then
                assertThat(failure.failure().effectiveCode()).isEqualTo("RESOURCE_INITIALIZATION_FAILED");
                assertThat(System.getProperty(HTTP_SENDER_PREFERENCE)).isNull();
                assertThat(System.getProperty(GRPC_SENDER_PREFERENCE)).isNull();
            }
        } finally {
            restoreSystemProperty(HTTP_SENDER_PREFERENCE, previousHttp);
            restoreSystemProperty(GRPC_SENDER_PREFERENCE, previousGrpc);
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
                    () -> recorder.primeWithConfig(invalid.properties())
            );
            BootstrapException repeated = catchThrowableOfType(
                    BootstrapException.class,
                    () -> recorder.primeWithConfig(invalid.properties())
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
            assertThat(first.failure().effectiveCode()).isEqualTo("CONFIGURATION_INITIALIZATION_FAILED");
            assertThat(first.getCause()).hasMessageContaining(invalid.expectedProperty());
            assertThat(first.failure().cause()).isSameAs(first.getCause());
            assertThat(repeated.failure().effectiveCode()).isEqualTo(first.failure().effectiveCode());
            assertThat(repeated.getCause()).isSameAs(first.getCause());
            assertThat(coordinator.state()).isEqualTo(BootstrapState.FAILED);
            assertThat(coordinator.currentWire().lifecycle()).isEqualTo("FAILED");
            assertThat(coordinator.currentWire().failureCode())
                    .isEqualTo("CONFIGURATION_INITIALIZATION_FAILED");
            assertThat(coordinator.currentWire().agentInstanceId()).isNotBlank();
            assertThat(coordinator.currentWire().batchId()).isEmpty();
            assertThat(coordinator.currentWire().evidenceGeneration()).isZero();
            assertThat(coordinator.currentWire().evidence().complete()).isFalse();

            assertThat(exporterFailure.failure().effectiveCode()).isEqualTo(first.failure().effectiveCode());
            assertThat(exporterFailure.getCause()).isSameAs(first.getCause());
            assertThat(processorFailure.failure().effectiveCode()).isEqualTo(first.failure().effectiveCode());
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

    private static BootstrapCoordinator coordinator() {
        return coordinator(MBeanServerFactory.newMBeanServer());
    }

    private static BootstrapCoordinator coordinator(MBeanServer server) {
        return new BootstrapCoordinator(
                (threadName, action) -> {
                },
                new PlatformScrubberFactory(),
                RuntimeEvidenceTestFixtures.trustedVerifier(),
                AutoconfigureSessionFactory::currentProcessRemoteJmxAssessor,
                new PlatformTracingMBeanRegistrar(server, AgentInstanceId.random()),
                new DiagnosticReadinessHandle()
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

    private static void restoreSystemProperty(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
            return;
        }
        System.setProperty(name, value);
    }

    private static final class Recorder {
        int samplerCustomizerRegistrations;
        BiFunction<Resource, ConfigProperties, Resource> resourceCustomizer;
        BiFunction<SdkTracerProviderBuilder, ConfigProperties, SdkTracerProviderBuilder> tracerProviderCustomizer;
        BiFunction<SpanExporter, ConfigProperties, SpanExporter> spanExporterCustomizer;
        BiFunction<SpanProcessor, ConfigProperties, SpanProcessor> spanProcessorCustomizer;
        Supplier<Map<String, String>> propertiesSupplier;

        private final List<Function<ConfigProperties, Map<String, String>>> propertiesCustomizers = new ArrayList<>();
        private final List<ConfigProperties> propertiesCustomizerInputs = new ArrayList<>();
        private final List<ConfigProperties> resourceCustomizerInputs = new ArrayList<>();

        ConfigProperties primeWithConfig(Map<String, String> initialProperties) {
            Map<String, String> effectiveProperties = new HashMap<>(initialProperties);
            ConfigProperties effectiveConfig = configProperties(effectiveProperties);

            for (Function<ConfigProperties, Map<String, String>> customizer : propertiesCustomizers) {
                propertiesCustomizerInputs.add(effectiveConfig);
                effectiveProperties.putAll(customizer.apply(effectiveConfig));
                effectiveConfig = configProperties(effectiveProperties);
            }

            resourceCustomizerInputs.add(effectiveConfig);
            resourceCustomizer.apply(Resource.empty(), effectiveConfig);
            return effectiveConfig;
        }

        private static ConfigProperties configProperties(Map<String, String> properties) {
            return DefaultConfigProperties.createFromMap(Map.copyOf(properties));
        }

        @SuppressWarnings("unchecked")
        AutoConfigurationCustomizer proxy() {
            return (AutoConfigurationCustomizer) Proxy.newProxyInstance(
                    AutoConfigurationCustomizer.class.getClassLoader(),
                    new Class<?>[]{AutoConfigurationCustomizer.class},
                    (Object proxyObj, Method method, Object[] args) -> switch (method.getName()) {
                        case "addSamplerCustomizer" -> {
                            samplerCustomizerRegistrations++;
                            yield proxyObj;
                        }

                        case "addResourceCustomizer" -> {
                            this.resourceCustomizer = (BiFunction<Resource, ConfigProperties, Resource>) args[0];
                            yield proxyObj;
                        }

                        case "addTracerProviderCustomizer" -> {
                            this.tracerProviderCustomizer =
                                    (BiFunction<SdkTracerProviderBuilder, ConfigProperties, SdkTracerProviderBuilder>)
                                            args[0];
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
                            this.spanExporterCustomizer = (BiFunction<SpanExporter, ConfigProperties, SpanExporter>) args[0];
                            yield proxyObj;
                        }

                        case "addSpanProcessorCustomizer" -> {
                            this.spanProcessorCustomizer = (BiFunction<SpanProcessor, ConfigProperties, SpanProcessor>) args[0];
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
}
