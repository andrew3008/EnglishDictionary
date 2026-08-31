package space.br1440.platform.tracing.otel.javaagent.factory;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionConfig;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.OwnerRegistrationSlot;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.OwnerRegistrationState;
import space.br1440.platform.tracing.otel.javaagent.jmx.registration.PlatformTracingMBeanRegistrar;
import space.br1440.platform.tracing.otel.javaagent.processor.SpanLimitsMetricsProcessor;
import space.br1440.platform.tracing.otel.javaagent.processor.ValidatingSpanProcessor;
import space.br1440.platform.tracing.otel.javaagent.processor.composite.PlatformCompositeSpanProcessor;
import space.br1440.platform.tracing.otel.javaagent.processor.watchdog.SpanWatchdogProcessor;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.PlatformScrubberFactory;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.ScrubbingRuntimeTestFixtures;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SdkTracerProviderBuilder;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

class PlatformSpanProcessorFactoryExtensionConfigAdoptionTest {

    private static ConfigProperties scrubbingDisabledConfig() {
        return new ConfigProperties() {
            @Override public String getString(String name) { return null; }
            @Override public Boolean getBoolean(String name) {
                // scrubbing.enabled = false so collectScrubbingRules is never called
                if ("platform.tracing.javaagent.scrubbing.enabled".equals(name)) return false;
                return null;
            }
            @Override public Integer getInt(String name) { return null; }
            @Override public Long getLong(String name) { return null; }
            @Override public Double getDouble(String name) { return null; }
            @Override public Duration getDuration(String name) { return null; }
            @Override public List<String> getList(String name) { return null; }
            @Override public Map<String, String> getMap(String name) { return null; }
        };
    }

    private static ExtensionConfig configWithAllDisabled() {
        return new ExtensionConfig(new ConfigProperties() {
            @Override public String getString(String name) { return null; }
            @Override public Boolean getBoolean(String name) { return false; }
            @Override public Integer getInt(String name) { return null; }
            @Override public Long getLong(String name) { return null; }
            @Override public Double getDouble(String name) { return null; }
            @Override public Duration getDuration(String name) { return null; }
            @Override public List<String> getList(String name) { return null; }
            @Override public Map<String, String> getMap(String name) { return null; }
        });
    }

    private static ExtensionConfig configWithMetricsEnabled() {
        return new ExtensionConfig(new ConfigProperties() {
            @Override public String getString(String name) { return null; }
            @Override public Boolean getBoolean(String name) {
                return "platform.tracing.metrics.enabled".equals(name);
            }
            @Override public Integer getInt(String name) { return null; }
            @Override public Long getLong(String name) { return null; }
            @Override public Double getDouble(String name) { return null; }
            @Override public Duration getDuration(String name) { return null; }
            @Override public List<String> getList(String name) { return null; }
            @Override public Map<String, String> getMap(String name) { return null; }
        });
    }

    private static ExtensionConfig configWithWatchdogEnabled() {
        return new ExtensionConfig(new ConfigProperties() {
            @Override public String getString(String name) { return null; }
            @Override public Boolean getBoolean(String name) {
                return "platform.tracing.watchdog.enabled".equals(name);
            }
            @Override public Integer getInt(String name) { return null; }
            @Override public Long getLong(String name) { return null; }
            @Override public Double getDouble(String name) { return null; }
            @Override public Duration getDuration(String name) { return null; }
            @Override public Map<String, String> getMap(String name) { return null; }
            @Override public List<String> getList(String name) { return null; }
        });
    }

    private static ExtensionConfig configWithValidationEnabled() {
        return new ExtensionConfig(new ConfigProperties() {
            @Override public String getString(String name) { return null; }
            @Override public Boolean getBoolean(String name) {
                return "platform.tracing.validation.enabled".equals(name);
            }
            @Override public Integer getInt(String name) { return null; }
            @Override public Long getLong(String name) { return null; }
            @Override public Double getDouble(String name) { return null; }
            @Override public Duration getDuration(String name) { return null; }
            @Override public List<String> getList(String name) { return null; }
            @Override public Map<String, String> getMap(String name) { return null; }
        });
    }

    private static ConfigProperties allEnabledConfig() {
        return new ConfigProperties() {
            @Override public String getString(String name) { return null; }
            @Override public Boolean getBoolean(String name) { return true; }
            @Override public Integer getInt(String name) { return null; }
            @Override public Long getLong(String name) { return null; }
            @Override public Double getDouble(String name) { return null; }
            @Override public Duration getDuration(String name) { return null; }
            @Override public List<String> getList(String name) { return null; }
            @Override public Map<String, String> getMap(String name) { return null; }
        };
    }

    @SuppressWarnings("unchecked")
    private static <T> T registrarSlotOwner(PlatformTracingMBeanRegistrar reg, String slotAccessorName) throws Exception {
        Field runtimePlaneField = PlatformTracingMBeanRegistrar.class.getDeclaredField("runtimePlane");
        runtimePlaneField.setAccessible(true);
        Object runtimePlane = runtimePlaneField.get(reg);

        Field slotsField = runtimePlane.getClass().getDeclaredField("slots");
        slotsField.setAccessible(true);
        Object slots = slotsField.get(runtimePlane);

        Method slotAccessor = slots.getClass().getDeclaredMethod(slotAccessorName);
        slotAccessor.setAccessible(true);
        OwnerRegistrationSlot<T> slot = (OwnerRegistrationSlot<T>) slotAccessor.invoke(slots);

        OwnerRegistrationState<T> state = slot.capture();
        return state instanceof OwnerRegistrationState.Present<T> present ? present.owner() : null;
    }

    private static SpanLimitsMetricsProcessor getMetrics(PlatformTracingMBeanRegistrar r) throws Exception {
        return registrarSlotOwner(r, "metrics");
    }

    private static SpanWatchdogProcessor getWatchdog(PlatformTracingMBeanRegistrar r) throws Exception {
        return registrarSlotOwner(r, "watchdog");
    }

    private static ValidatingSpanProcessor getValidating(PlatformTracingMBeanRegistrar r) throws Exception {
        return registrarSlotOwner(r, "validating");
    }

    private static PlatformCompositeSpanProcessor getComposite(PlatformTracingMBeanRegistrar r) throws Exception {
        return registrarSlotOwner(r, "composite");
    }

    private static Object firstCompositeDelegate(PlatformCompositeSpanProcessor composite) throws Exception {
        Field delegatesField = PlatformCompositeSpanProcessor.class.getDeclaredField("delegates");
        delegatesField.setAccessible(true);
        List<?> delegates = (List<?>) delegatesField.get(composite);

        Object namedDelegate = delegates.getFirst();
        Method delegateAccessor = namedDelegate.getClass().getDeclaredMethod("delegate");
        delegateAccessor.setAccessible(true);
        return delegateAccessor.invoke(namedDelegate);
    }

    @Nested
    @DisplayName("Optional subsystems disabled → passive validation carrier remains")
    class AllDisabled {

        @Test
        void composite_contains_only_passive_validation_carrier() throws Exception {
            PlatformTracingMBeanRegistrar registrar = new PlatformTracingMBeanRegistrar();
            PlatformSpanProcessorFactory factory = new PlatformSpanProcessorFactory(
                    registrar, ScrubbingRuntimeTestFixtures.disabled());

            ExtensionConfig extensionConfig = configWithAllDisabled();
            factory.registerSpanProcessors(
                    SdkTracerProvider.builder(),
                    extensionConfig,
                    scrubbingDisabledConfig(),
                    TracingSettingsSource.fromStartup(extensionConfig));

            assertThat(getComposite(registrar).getProcessorErrorCounts().keySet())
                    .as("runtime validation policy requires a permanently installed processor")
                    .containsExactly("validation");
        }

        @Test
        void validation_owner_is_the_processor_installed_in_composite() throws Exception {
            PlatformTracingMBeanRegistrar registrar = new PlatformTracingMBeanRegistrar();
            PlatformSpanProcessorFactory factory = new PlatformSpanProcessorFactory(
                    registrar, ScrubbingRuntimeTestFixtures.disabled());

            ExtensionConfig extensionConfig = configWithAllDisabled();
            factory.registerSpanProcessors(
                    SdkTracerProvider.builder(),
                    extensionConfig,
                    scrubbingDisabledConfig(),
                    TracingSettingsSource.fromStartup(extensionConfig));

            ValidatingSpanProcessor validating = getValidating(registrar);
            assertThat(validating).isNotNull();
            assertThat(firstCompositeDelegate(getComposite(registrar))).isSameAs(validating);
        }

        @Test
        void no_metrics_set_when_all_disabled() throws Exception {
            PlatformTracingMBeanRegistrar registrar = new PlatformTracingMBeanRegistrar();
            PlatformSpanProcessorFactory factory = new PlatformSpanProcessorFactory(
                    registrar, ScrubbingRuntimeTestFixtures.disabled());

            ExtensionConfig extensionConfig = configWithAllDisabled();
            factory.registerSpanProcessors(
                    SdkTracerProvider.builder(),
                    extensionConfig,
                    scrubbingDisabledConfig(),
                    TracingSettingsSource.fromStartup(extensionConfig));

            assertThat(getMetrics(registrar)).as("no metrics when all disabled").isNull();
        }

        @Test
        void no_watchdog_set_when_all_disabled() throws Exception {
            PlatformTracingMBeanRegistrar registrar = new PlatformTracingMBeanRegistrar();
            PlatformSpanProcessorFactory factory = new PlatformSpanProcessorFactory(
                    registrar, ScrubbingRuntimeTestFixtures.disabled());

            ExtensionConfig extensionConfig = configWithAllDisabled();
            factory.registerSpanProcessors(
                    SdkTracerProvider.builder(),
                    extensionConfig,
                    scrubbingDisabledConfig(),
                    TracingSettingsSource.fromStartup(extensionConfig));

            assertThat(getWatchdog(registrar)).as("no watchdog when all disabled").isNull();
        }
    }

    @Test
    void factory_preserves_required_on_ending_order() throws Exception {
        // given
        PlatformTracingMBeanRegistrar registrar = new PlatformTracingMBeanRegistrar();
        ConfigProperties properties = allEnabledConfig();
        ExtensionConfig extensionConfig = new ExtensionConfig(properties);
        PlatformSpanProcessorFactory factory = new PlatformSpanProcessorFactory(
                registrar, new PlatformScrubberFactory().buildRuntime(extensionConfig, properties));

        // when
        factory.registerSpanProcessors(
                SdkTracerProvider.builder(),
                extensionConfig,
                properties,
                TracingSettingsSource.fromStartup(extensionConfig));

        PlatformCompositeSpanProcessor composite = getComposite(registrar);
        // then
        try {
            // Инвариант ADR относится к фазе onEnding, а не к общему списку делегатов: только в
            // onEnding span ещё изменяем, и scrubbing обязан идти после всех, кто его пишет.
            List<String> onEndingOrder = composite.getOnEndingDelegateOrder();
            assertThat(onEndingOrder).contains("validation", "enrichment", "scrubbing");
            assertThat(onEndingOrder.getLast())
                    .as("scrubbing must be the last onEnding delegate in factory composition")
                    .isEqualTo("scrubbing");
            assertThat(onEndingOrder.indexOf("validation"))
                    .as("validation must observe the span before enrichment normalizes it")
                    .isLessThan(onEndingOrder.indexOf("enrichment"));
            assertThat(onEndingOrder.indexOf("enrichment"))
                    .isLessThan(onEndingOrder.indexOf("scrubbing"));
            assertThat(onEndingOrder.indexOf("validation"))
                    .isLessThan(onEndingOrder.indexOf("scrubbing"));

            List<String> order = List.copyOf(composite.getProcessorErrorCounts().keySet());
            assertThat(order.indexOf("classification"))
                    .isLessThan(order.indexOf("watchdog"));
            assertThat(order.indexOf("watchdog"))
                    .isLessThan(order.indexOf("span_limits_metrics"));
            assertThat(order.indexOf("watchdog"))
                    .isLessThan(order.indexOf("scrubbing"));
        } finally {
            composite.shutdown();
        }
    }

    @Test
    void factory_backed_pipeline_recordsValidationMarker_onlyForSpanWithoutProducerResult() throws Exception {
        // given
        PlatformTracingMBeanRegistrar registrar = new PlatformTracingMBeanRegistrar();
        ConfigProperties properties = allEnabledConfig();
        ExtensionConfig extensionConfig = new ExtensionConfig(properties);
        PlatformSpanProcessorFactory factory = new PlatformSpanProcessorFactory(
                registrar, new PlatformScrubberFactory().buildRuntime(extensionConfig, properties));

        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        SdkTracerProviderBuilder builder = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter));

        factory.registerSpanProcessors(
                builder,
                extensionConfig,
                properties,
                TracingSettingsSource.fromStartup(extensionConfig));

        ValidatingSpanProcessor validating = getValidating(registrar);
        long violationsBefore = validating.getValidationViolationsTotal();

        try (OpenTelemetrySdk sdk = OpenTelemetrySdk.builder().setTracerProvider(builder.build()).build()) {
            Tracer tracer = sdk.getTracer("t");

            // when
            Span withoutProducerResult = tracer.spanBuilder("server-op-without-result")
                    .setSpanKind(SpanKind.SERVER)
                    .startSpan();
            withoutProducerResult.end();

            Span withProducerResult = tracer.spanBuilder("server-op-with-result")
                    .setSpanKind(SpanKind.SERVER)
                    .startSpan();
            withProducerResult.setAttribute(
                    AttributeKey.stringKey(PlatformAttributes.PLATFORM_RESULT), "success");
            withProducerResult.end();

            sdk.getSdkTracerProvider().forceFlush().join(2, TimeUnit.SECONDS);

            List<SpanData> spans = exporter.getFinishedSpanItems();
            SpanData withoutResultData = spanNamed(spans, "server-op-without-result");
            SpanData withResultData = spanNamed(spans, "server-op-with-result");

            // then
            AttributeKey<String> missingKey = AttributeKey.stringKey("platform.validation.missing");
            assertThat(withoutResultData.getAttributes().get(missingKey))
                    .isEqualTo(PlatformAttributes.PLATFORM_RESULT);
            assertThat(withResultData.getAttributes().get(missingKey))
                    .as("negative control: producer-declared result must not be flagged as missing")
                    .isNull();
            assertThat(withoutResultData.getAttributes().get(AttributeKey.stringKey(PlatformAttributes.PLATFORM_RESULT)))
                    .isEqualTo("success");
            assertThat(withResultData.getAttributes().get(AttributeKey.stringKey(PlatformAttributes.PLATFORM_RESULT)))
                    .isEqualTo("success");

            long violationsAfter = validating.getValidationViolationsTotal();
            assertThat(violationsAfter - violationsBefore)
                    .as("exactly one violation for the span without a producer-declared result")
                    .isEqualTo(1L);
        }
    }

    private static SpanData spanNamed(List<SpanData> spans, String name) {
        return spans.stream()
                .filter(span -> span.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Span not found: " + name));
    }

    @Nested
    @DisplayName("Individual subsystem enabled → correct processor registered")
    class IndividualEnabled {

        @Test
        void metrics_enabled_registers_metrics_processor() throws Exception {
            PlatformTracingMBeanRegistrar registrar = new PlatformTracingMBeanRegistrar();
            PlatformSpanProcessorFactory factory = new PlatformSpanProcessorFactory(
                    registrar, ScrubbingRuntimeTestFixtures.disabled());

            ExtensionConfig extensionConfig = configWithMetricsEnabled();
            factory.registerSpanProcessors(
                    SdkTracerProvider.builder(),
                    extensionConfig,
                    scrubbingDisabledConfig(),
                    TracingSettingsSource.fromStartup(extensionConfig));

            assertThat(getMetrics(registrar))
                    .as("metrics enabled → SpanLimitsMetricsProcessor set on registrar")
                    .isNotNull()
                    .isInstanceOf(SpanLimitsMetricsProcessor.class);
        }

        @Test
        void metrics_enabled_registers_composite() throws Exception {
            PlatformTracingMBeanRegistrar registrar = new PlatformTracingMBeanRegistrar();
            PlatformSpanProcessorFactory factory = new PlatformSpanProcessorFactory(
                    registrar, ScrubbingRuntimeTestFixtures.disabled());

            ExtensionConfig extensionConfig = configWithMetricsEnabled();
            factory.registerSpanProcessors(
                    SdkTracerProvider.builder(),
                    extensionConfig,
                    scrubbingDisabledConfig(),
                    TracingSettingsSource.fromStartup(extensionConfig));

            assertThat(getComposite(registrar))
                    .as("at least one processor → composite set on registrar")
                    .isNotNull();
        }

        @Test
        void watchdog_enabled_registers_watchdog_processor() throws Exception {
            PlatformTracingMBeanRegistrar registrar = new PlatformTracingMBeanRegistrar();
            PlatformSpanProcessorFactory factory = new PlatformSpanProcessorFactory(
                    registrar, ScrubbingRuntimeTestFixtures.disabled());

            ExtensionConfig extensionConfig = configWithWatchdogEnabled();
            factory.registerSpanProcessors(
                    SdkTracerProvider.builder(),
                    extensionConfig,
                    scrubbingDisabledConfig(),
                    TracingSettingsSource.fromStartup(extensionConfig));

            assertThat(getWatchdog(registrar))
                    .as("watchdog enabled → SpanWatchdogProcessor set on registrar")
                    .isNotNull()
                    .isInstanceOf(SpanWatchdogProcessor.class);
        }

        @Test
        void validation_enabled_registers_validating_processor() throws Exception {
            PlatformTracingMBeanRegistrar registrar = new PlatformTracingMBeanRegistrar();
            PlatformSpanProcessorFactory factory = new PlatformSpanProcessorFactory(
                    registrar, ScrubbingRuntimeTestFixtures.disabled());

            ExtensionConfig extensionConfig = configWithValidationEnabled();
            factory.registerSpanProcessors(
                    SdkTracerProvider.builder(),
                    extensionConfig,
                    scrubbingDisabledConfig(),
                    TracingSettingsSource.fromStartup(extensionConfig));

            assertThat(getValidating(registrar))
                    .as("validation enabled → ValidatingSpanProcessor set on registrar")
                    .isNotNull()
                    .isInstanceOf(ValidatingSpanProcessor.class);
        }
    }

    @Nested
    @DisplayName("Default config (no properties set) — watchdog not added")
    class DefaultConfig {

        @Test
        void factoryAddsBaggageProcessorWithCanonicalDefaults() throws Exception {
            PlatformTracingMBeanRegistrar registrar = new PlatformTracingMBeanRegistrar();
            PlatformSpanProcessorFactory factory = new PlatformSpanProcessorFactory(
                    registrar, ScrubbingRuntimeTestFixtures.disabled());
            ExtensionConfig defaultConfig = new ExtensionConfig(new ConfigProperties() {
                @Override public String getString(String name) { return null; }
                @Override public Boolean getBoolean(String name) {
                    if ("platform.tracing.javaagent.scrubbing.enabled".equals(name)) return false;
                    return null;
                }
                @Override public Integer getInt(String name) { return null; }
                @Override public Long getLong(String name) { return null; }
                @Override public Double getDouble(String name) { return null; }
                @Override public Duration getDuration(String name) { return null; }
                @Override public List<String> getList(String name) { return null; }
                @Override public Map<String, String> getMap(String name) { return null; }
            });

            factory.registerSpanProcessors(
                    SdkTracerProvider.builder(),
                    defaultConfig,
                    scrubbingDisabledConfig(),
                    TracingSettingsSource.fromStartup(defaultConfig));

            assertThat(getComposite(registrar).getProcessorErrorCounts())
                    .containsKey("baggage");
        }

        @Test
        void factoryDoesNotAddWatchdogProcessorByDefault() throws Exception {
            PlatformTracingMBeanRegistrar registrar = new PlatformTracingMBeanRegistrar();
            PlatformSpanProcessorFactory factory = new PlatformSpanProcessorFactory(
                    registrar, ScrubbingRuntimeTestFixtures.disabled()
            );

            ExtensionConfig defaultConfig = new ExtensionConfig(new ConfigProperties() {
                @Override public String getString(String name) { return null; }
                @Override public Boolean getBoolean(String name) {
                    if ("platform.tracing.javaagent.scrubbing.enabled".equals(name)) {
                        return false;
                    }

                    return null;
                }
                @Override public Integer getInt(String name) { return null; }
                @Override public Long getLong(String name) { return null; }
                @Override public Double getDouble(String name) { return null; }
                @Override public Duration getDuration(String name) { return null; }
                @Override public List<String> getList(String name) { return null; }
                @Override public Map<String, String> getMap(String name) { return null; }
            });

            factory.registerSpanProcessors(
                    SdkTracerProvider.builder(),
                    defaultConfig,
                    scrubbingDisabledConfig(),
                    TracingSettingsSource.fromStartup(defaultConfig));

            assertThat(getWatchdog(registrar))
                    .as("watchdog must not be registered with default config (SP-01 default-off)")
                    .isNull();
        }
    }

    @Nested
    @DisplayName("Parity: ExtensionConfig values match old direct-read behavior")
    class DefaultParity {

        @Test
        @DisplayName("Default ExtensionConfig has same effective defaults as old ExtensionDefaults")
        void default_watchdog_timeouts_match_extension_defaults() {
            ExtensionConfig cfg = new ExtensionConfig(new ConfigProperties() {
                @Override public String getString(String n) { return null; }
                @Override public Boolean getBoolean(String n) { return null; }
                @Override public Integer getInt(String n) { return null; }
                @Override public Long getLong(String n) { return null; }
                @Override public Double getDouble(String n) { return null; }
                @Override public Duration getDuration(String n) { return null; }
                @Override public List<String> getList(String n) { return null; }
                @Override public Map<String, String> getMap(String n) { return null; }
            });

            assertThat(cfg.watchdog().spanTimeout())
                    .as("watchdog spanTimeout default")
                    .isEqualTo(Duration.ofSeconds(30));
            assertThat(cfg.watchdog().traceTimeout())
                    .as("watchdog traceTimeout default")
                    .isEqualTo(Duration.ofSeconds(60));
            assertThat(cfg.watchdog().scanInterval())
                    .as("watchdog scanInterval default")
                    .isEqualTo(Duration.ofSeconds(5));
            assertThat(cfg.watchdog().maxTrackedTraces())
                    .as("watchdog maxTrackedTraces default")
                    .isEqualTo(10_000);
            assertThat(cfg.classification().slowThreshold())
                    .as("classification slowThreshold default")
                    .isEqualTo(Duration.ofSeconds(5));
            assertThat(cfg.classification().normalThreshold())
                    .as("classification normalThreshold default")
                    .isEqualTo(Duration.ofSeconds(1));
        }

        @Test
        @DisplayName("Enriching remoteServicePriority default matches old ExtensionDefaults")
        void default_enriching_priority_matches_extension_defaults() {
            ExtensionConfig cfg = new ExtensionConfig(new ConfigProperties() {
                @Override public String getString(String n) { return null; }
                @Override public Boolean getBoolean(String n) { return null; }
                @Override public Integer getInt(String n) { return null; }
                @Override public Long getLong(String n) { return null; }
                @Override public Double getDouble(String n) { return null; }
                @Override public Duration getDuration(String n) { return null; }
                @Override public List<String> getList(String n) { return null; }
                @Override public Map<String, String> getMap(String n) { return null; }
            });

            assertThat(cfg.enriching().remoteServicePriority())
                    .as("enriching.remoteServicePriority default")
                    .containsExactly("peer.service", "rpc.service", "server.address");
        }

        @Test
        @DisplayName("Baggage defaults: enabled with canonical allowlist and deny patterns")
        void default_baggage_matches_extension_defaults() {
            ExtensionConfig cfg = new ExtensionConfig(new ConfigProperties() {
                @Override public String getString(String n) { return null; }
                @Override public Boolean getBoolean(String n) { return null; }
                @Override public Integer getInt(String n) { return null; }
                @Override public Long getLong(String n) { return null; }
                @Override public Double getDouble(String n) { return null; }
                @Override public Duration getDuration(String n) { return null; }
                @Override public List<String> getList(String n) { return null; }
                @Override public Map<String, String> getMap(String n) { return null; }
            });

            assertThat(cfg.baggage().processorEnabled()).as("baggage.processor.enabled default").isTrue();
            assertThat(cfg.baggage().processorAllowedKeys())
                    .as("baggage.processor.allowedKeys default")
                    .containsExactly("traffic_source", "tenant_class", "platform.correlation.id");
            assertThat(cfg.baggage().processorDenyPatterns())
                    .as("baggage.processor.denyPatterns default")
                    .containsExactly("password", "secret", "token");
        }
    }
}
