package space.br1440.platform.tracing.otel.extension;

import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;
import lombok.extern.slf4j.Slf4j;
import space.br1440.platform.tracing.otel.extension.configuration.ExtensionConfig;
import space.br1440.platform.tracing.otel.extension.configuration.spi.AutoConfigurationCustomizerOrdering;
import space.br1440.platform.tracing.otel.extension.configuration.spi.PlatformTracingDefaultsProvider;
import space.br1440.platform.tracing.otel.extension.factory.PlatformExportProcessorFactory;
import space.br1440.platform.tracing.otel.extension.propagation.BaggagePropagationCustomizer;
import space.br1440.platform.tracing.otel.extension.factory.PlatformSamplerFactory;
import space.br1440.platform.tracing.otel.extension.factory.PlatformSpanProcessorFactory;
import space.br1440.platform.tracing.otel.extension.jmx.PlatformTracingJmxRegistrar;
import space.br1440.platform.tracing.otel.extension.propagation.PlatformPropagatorsDefaultsCustomizer;
import space.br1440.platform.tracing.otel.extension.utils.Strings;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
public class PlatformAutoConfigurationCustomizer implements AutoConfigurationCustomizerProvider {

    private final PlatformTracingDefaultsProvider defaultsProvider = new PlatformTracingDefaultsProvider();

    private final PlatformTracingJmxRegistrar jmxRegistrar = new PlatformTracingJmxRegistrar();
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
        // Платформенные дефолты для свойств OTel SDK (BSP queue/timeout, span limits).
        customizer.addPropertiesSupplier(defaultsProvider::supply);

        // Bootstrap: создаём ExtensionConfig ровно один раз из финального merged ConfigProperties.
        // Все последующие factory callbacks видят уже готовый extensionConfig.get().
        // Диагностика sdk.mode совмещена сюда, чтобы не делать отдельный проход.
        customizer.addPropertiesCustomizer(config -> {
            extensionConfig.compareAndSet(null, new ExtensionConfig(config));
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

        customizer.addPropagatorCustomizer(baggageCustomizer::apply);

        // PR-5: SamplingExtensionConfig передаётся из bootstrap extensionConfig; ConfigProperties
        // sampler-customizer'а игнорируется для sampling-домена (уже прочитан в addPropertiesCustomizer).
        customizer.addSamplerCustomizer((existing, config) ->
                samplerFactory.buildSampler(existing, extensionConfig.get().sampling()));

        // ExtensionConfig передаётся в фабрику процессоров; ConfigProperties сохраняется для
        // scrubbing-пути (PR-4). Нельзя использовать метод-референс — сигнатура изменилась.
        customizer.addTracerProviderCustomizer((builder, config) ->
                spanProcessorFactory.registerSpanProcessors(builder, extensionConfig.get(), config));

        // SPI addSpanExporterCustomizer требует BiFunction<SpanExporter, ConfigProperties, SpanExporter>;
        // config на этапе capture не используется — адаптация сигнатуры здесь, не в factory.
        customizer.addSpanExporterCustomizer((exporter, config) -> exportProcessorFactory.captureExporter(exporter));

        // Opt-in замена стандартного BatchSpanProcessor на платформенный.
        // QueueExtensionConfig — из bootstrap ExtensionConfig; config — только для otel.bsp.* OTel-ключей.
        customizer.addSpanProcessorCustomizer((processor, config) ->
                exportProcessorFactory.maybeReplaceExportProcessor(processor, extensionConfig.get().queue(), config));
    }
}
