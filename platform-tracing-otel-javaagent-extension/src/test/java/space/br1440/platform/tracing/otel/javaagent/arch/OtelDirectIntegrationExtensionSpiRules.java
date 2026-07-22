package space.br1440.platform.tracing.otel.javaagent.arch;

import com.tngtech.archunit.lang.ArchRule;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;
import io.opentelemetry.sdk.autoconfigure.spi.ResourceProvider;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

/**
 * Rule 4: positive SPI assertions для platform SDK-компонентов в otel-extension.
 * <p>
 * Живёт в test source otel-extension, потому что требует {@code autoconfigure-spi} в test classpath.
 * Модули core/autoconfigure используют только {@link space.br1440.platform.tracing.test.arch.OtelDirectIntegrationRules}.
 */
public final class OtelDirectIntegrationExtensionSpiRules {

    private OtelDirectIntegrationExtensionSpiRules() {
    }

    public static final ArchRule BAGGAGE_SPAN_PROCESSOR_IMPLEMENTS_SPI = classes()
            .that().haveSimpleName("BaggageSpanProcessor")
            .and().resideInAPackage("space.br1440.platform.tracing..")
            .should().beAssignableTo(SpanProcessor.class);

    public static final ArchRule ENRICHING_SPAN_PROCESSOR_IMPLEMENTS_SPI = classes()
            .that().haveSimpleName("EnrichingSpanProcessor")
            .and().resideInAPackage("space.br1440.platform.tracing..")
            .should().beAssignableTo(SpanProcessor.class);

    public static final ArchRule SCRUBBING_SPAN_PROCESSOR_IMPLEMENTS_SPI = classes()
            .that().haveSimpleName("ScrubbingSpanProcessor")
            .and().resideInAPackage("space.br1440.platform.tracing..")
            .should().beAssignableTo(SpanProcessor.class);

    public static final ArchRule VALIDATING_SPAN_PROCESSOR_IMPLEMENTS_SPI = classes()
            .that().haveSimpleName("ValidatingSpanProcessor")
            .and().resideInAPackage("space.br1440.platform.tracing..")
            .should().beAssignableTo(SpanProcessor.class);

    public static final ArchRule SPAN_WATCHDOG_PROCESSOR_IMPLEMENTS_SPI = classes()
            .that().haveSimpleName("SpanWatchdogProcessor")
            .and().resideInAPackage("space.br1440.platform.tracing..")
            .should().beAssignableTo(SpanProcessor.class);

    public static final ArchRule PLATFORM_COMPOSITE_SPAN_PROCESSOR_IMPLEMENTS_SPI = classes()
            .that().haveSimpleName("PlatformCompositeSpanProcessor")
            .and().resideInAPackage("space.br1440.platform.tracing..")
            .should().beAssignableTo(SpanProcessor.class);

    public static final ArchRule COMPOSITE_SAMPLER_IMPLEMENTS_SPI = classes()
            .that().haveSimpleName("CompositeSampler")
            .and().resideInAPackage("space.br1440.platform.tracing..")
            .should().beAssignableTo(Sampler.class);

    public static final ArchRule PLATFORM_RESOURCE_PROVIDER_IMPLEMENTS_SPI = classes()
            .that().haveSimpleName("PlatformResourceProvider")
            .and().resideInAPackage("space.br1440.platform.tracing..")
            .should().beAssignableTo(ResourceProvider.class);

    public static final ArchRule PLATFORM_AUTO_CONFIGURATION_CUSTOMIZER_IMPLEMENTS_SPI = classes()
            .that().haveSimpleName("PlatformAutoConfigurationCustomizer")
            .and().resideInAPackage("space.br1440.platform.tracing..")
            .should().beAssignableTo(AutoConfigurationCustomizerProvider.class);
}
