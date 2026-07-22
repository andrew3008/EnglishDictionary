package space.br1440.platform.tracing.otel.javaagent;

import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import lombok.extern.slf4j.Slf4j;
import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionConfig;
import space.br1440.platform.tracing.otel.javaagent.configuration.spi.AutoConfigurationCustomizerOrdering;
import space.br1440.platform.tracing.otel.javaagent.configuration.spi.PlatformTracingDefaultsProvider;
import space.br1440.platform.tracing.otel.javaagent.factory.PlatformExportProcessorFactory;
import space.br1440.platform.tracing.otel.javaagent.propagation.BaggagePropagationCustomizer;
import space.br1440.platform.tracing.otel.javaagent.factory.PlatformSamplerFactory;
import space.br1440.platform.tracing.otel.javaagent.factory.PlatformSpanProcessorFactory;
import space.br1440.platform.tracing.otel.javaagent.jmx.PlatformTracingJmxRegistrar;
import space.br1440.platform.tracing.otel.javaagent.propagation.PlatformPropagatorsDefaultsCustomizer;
import space.br1440.platform.tracing.otel.javaagent.utils.Strings;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionCapability;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionReadiness;
import space.br1440.platform.tracing.otel.javaagent.sampler.PlatformManagedSamplers;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
public class PlatformAutoConfigurationCustomizer implements AutoConfigurationCustomizerProvider {

    private final PlatformTracingDefaultsProvider defaultsProvider = new PlatformTracingDefaultsProvider();

    private final PlatformTracingJmxRegistrar jmxRegistrar = new PlatformTracingJmxRegistrar();
    private final PlatformExtensionReadiness readiness = jmxRegistrar.extensionReadiness();
    private final PlatformSamplerFactory samplerFactory = new PlatformSamplerFactory(jmxRegistrar);
    private final PlatformSpanProcessorFactory spanProcessorFactory = new PlatformSpanProcessorFactory(jmxRegistrar);
    private final PlatformExportProcessorFactory exportProcessorFactory = new PlatformExportProcessorFactory(jmxRegistrar);
    private final BaggagePropagationCustomizer baggageCustomizer = new BaggagePropagationCustomizer();
    private final PlatformPropagatorsDefaultsCustomizer propagatorsDefaults = new PlatformPropagatorsDefaultsCustomizer();

    /**
     * Единственный экземпляр {@link ExtensionConfig} для этого bootstrap-прохода.
     * Создаётся в первом {@code addPropertiesCustomizer} (до любых factory callbacks)
     * и переиспользуется всеми factory lambdas.
     */
    private final AtomicReference<ExtensionConfig> extensionConfig = new AtomicReference<>();

    // Одноразовый guard для диагностической лог-строки platform.tracing.sdk.mode.
    private final AtomicBoolean sdkModeLogged = new AtomicBoolean();

    @Override
    public int order() {
        return AutoConfigurationCustomizerOrdering.PLATFORM_EXTENSION_ORDER;
    }

    @Override
    public void customize(AutoConfigurationCustomizer customizer) {
        if (isJavaAgentRuntime()) {
            jmxRegistrar.installShutdownCleanup();
        }

        // Платформенные дефолты для свойств OTel SDK (BSP queue/timeout, span limits).
        customizer.addPropertiesSupplier(defaultsProvider::supply);

        // Bootstrap: создаём ExtensionConfig ровно один раз из финального merged ConfigProperties.
        // Все последующие factory callbacks видят уже готовый extensionConfig.get().
        // Диагностика sdk.mode совмещена сюда, чтобы не делать отдельный проход.
        customizer.addPropertiesCustomizer(config -> {
            try {
                extensionConfig.compareAndSet(null, new ExtensionConfig(config));
                readiness.markInstalled(PlatformExtensionCapability.CONFIGURATION_LOADED);
                if (!extensionConfig.get().scrubbing().enabled()) {
                    IllegalStateException failure = new IllegalStateException(
                            "platform.tracing.scrubbing.enabled=false is forbidden by the secure Agent profile");
                    readiness.fail("SANITIZER_DISABLED_FOR_SECURE_PROFILE", failure);
                    throw failure;
                }
            } catch (RuntimeException | Error failure) {
                readiness.fail("CONFIGURATION_INITIALIZATION_FAILED", failure);
                throw failure;
            }
            if (sdkModeLogged.compareAndSet(false, true)) {
                String mode = extensionConfig.get().sdk().mode();
                if (Strings.isNotBlank(mode)) {
                    log.info("Platform tracing extension: platform.tracing.sdk.mode={} (agent-канал, диагностика)", mode);
                }
            }
            return Map.of();
        });

        // ENV-aware дефолт otel.propagators: дописывает named platform-trace-control, если оператор
        // его не указал и не отключил всё через none. addPropertiesCustomizer (а не Supplier) —
        // читает уже смерженный конфиг, включая OTEL_PROPAGATORS (Фаза 15, PR-2).
        customizer.addPropertiesCustomizer(propagatorsDefaults);

        customizer.addPropagatorCustomizer((propagator, config) -> {
            try {
                TextMapPropagator customized = baggageCustomizer.apply(propagator, config);
                boolean platformControlVisible = customized.fields().stream()
                        .anyMatch(field -> "x-trace-on".equalsIgnoreCase(field));
                if (platformControlVisible) {
                    readiness.markInstalled(PlatformExtensionCapability.PROPAGATION_HOOKS_INSTALLED);
                }
                return customized;
            } catch (RuntimeException | Error failure) {
                readiness.fail("PROPAGATION_INITIALIZATION_FAILED", failure);
                throw failure;
            }
        });

        // PR-5: SamplingExtensionConfig передаётся из bootstrap extensionConfig; ConfigProperties
        // sampler-customizer'а игнорируется для sampling-домена (уже прочитан в addPropertiesCustomizer).
        customizer.addSamplerCustomizer((existing, config) -> {
            try {
                Sampler sampler = samplerFactory.buildSampler(existing, extensionConfig.get().sampling());
                if (PlatformManagedSamplers.isPlatformManaged(sampler)) {
                    readiness.markInstalled(PlatformExtensionCapability.PLATFORM_SAMPLER_INSTALLED);
                }
                return sampler;
            } catch (RuntimeException | Error failure) {
                readiness.fail("SAMPLER_INITIALIZATION_FAILED", failure);
                throw failure;
            }
        });

        // ExtensionConfig передаётся в фабрику процессоров; ConfigProperties сохраняется для
        // scrubbing-пути (PR-4). Нельзя использовать метод-референс — сигнатура изменилась.
        customizer.addTracerProviderCustomizer((builder, config) -> {
            try {
                return spanProcessorFactory.registerSpanProcessors(builder, extensionConfig.get(), config);
            } catch (RuntimeException | Error failure) {
                readiness.fail("SPAN_PROCESSOR_INITIALIZATION_FAILED", failure);
                throw failure;
            }
        });

        // SPI addSpanExporterCustomizer требует BiFunction<SpanExporter, ConfigProperties, SpanExporter>;
        // config на этапе capture не используется — адаптация сигнатуры здесь, не в factory.
        customizer.addSpanExporterCustomizer((exporter, config) -> {
            try {
                return exportProcessorFactory.captureExporter(exporter);
            } catch (RuntimeException | Error failure) {
                readiness.fail("EXPORTER_INITIALIZATION_FAILED", failure);
                throw failure;
            }
        });

        // Opt-in замена стандартного BatchSpanProcessor на платформенный.
        // QueueExtensionConfig — из bootstrap ExtensionConfig; config — только для otel.bsp.* OTel-ключей.
        customizer.addSpanProcessorCustomizer((processor, config) -> {
            try {
                return exportProcessorFactory.maybeReplaceExportProcessor(
                        processor, extensionConfig.get().queue(), config);
            } catch (RuntimeException | Error failure) {
                readiness.fail("EXPORT_PROCESSOR_INITIALIZATION_FAILED", failure);
                throw failure;
            }
        });
    }

    private static boolean isJavaAgentRuntime() {
        try {
            Class.forName("io.opentelemetry.javaagent.OpenTelemetryAgent", false,
                    PlatformAutoConfigurationCustomizer.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException ignored) {
            return false;
        }
    }
}
